package com.lifecontrol.api.fiscal.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.cancellation.CancellationRequest;
import com.lifecontrol.api.fiscal.cancellation.CfdiCancellationRequestBuilder;
import com.lifecontrol.api.fiscal.cancellation.CfdiCancellationSealer;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationProvider;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationRequest;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationResult;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentCancellation;
import com.lifecontrol.api.fiscal.exception.FiscalCancellationNotConfiguredException;
import com.lifecontrol.api.fiscal.exception.FiscalCancellationRejectedException;
import com.lifecontrol.api.fiscal.exception.FiscalDocumentAlreadyCancelledException;
import com.lifecontrol.api.fiscal.exception.FiscalDocumentNotStampedException;
import com.lifecontrol.api.fiscal.exception.FiscalSubstitutingFolioUnknownException;
import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import com.lifecontrol.api.fiscal.model.FiscalDocument;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentArtifactRepository;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels one stamped document at the authority and writes the row that says so — {@code T8d}'s service, the
 * cancelling half of {@code T8} of {@code odd/tasks/cfdi-fiscal-document.md}, whose contract is decisions
 * {@code L20}(a)–(g) of the same record.
 *
 * <p><b>The cancellation is a new row and the stamped one is never touched ({@code S7}).</b> A stamped document is
 * the authority's statement about a sale, and {@code V108}'s guard refuses every {@code UPDATE} of it — so what this
 * class writes is a <b>second</b> row, built by
 * {@link FiscalDocument#cancellationOf(FiscalDocument, FiscalCancellationMotive, UUID, String)}, that names the
 * cancelled document through {@code cancels_fiscal_document_id} and carries the motive and — when the motive
 * replaces it — the row of the document that substitutes it. The stamped row therefore stays byte-identical,
 * artefact included, which is what makes a reprint of a cancelled invoice still serve the bytes that were stamped.</p>
 *
 * <p><b>The order of the nine steps is the rule, and every step exists because a later one cannot answer what it
 * answers.</b></p>
 *
 * <ol>
 *   <li><b>The tenant comes from the scope</b> ({@link TenantScope}), stated by the request filter for a path that
 *       names no company, exactly as the reprint reads it.</li>
 *   <li><b>The document is read by {@code (company, id)} and must be a stamped sale's document with its artefact.</b>
 *       Absent, another company's, a {@code DRAFT}, or a stamped row that kept no bytes all answer
 *       {@link FiscalDocumentNotStampedException}, a <b>404</b> — which is the reprint's own answer for the reprint's
 *       own reason: the policy makes another tenant's row invisible, so "not found" is what the query <i>answers</i>,
 *       and a 403 would need a second read outside the policy to learn that the row exists.</li>
 *   <li><b>A document that is already cancelled is refused with a 409 before the authority is called</b>
 *       ({@link FiscalDocumentAlreadyCancelledException}). {@code uq_fiscal_document_cancels} states the rule, and it
 *       would refuse the second row anyway — but only <b>after</b> the SAT had registered a second cancellation this
 *       transaction is about to lose. The read is what turns "the transaction rolled back" into "nothing was
 *       sent".</li>
 *   <li><b>The substituting folio resolves to a row of this company, and only when one is stated.</b>
 *       {@code replaces_fiscal_document_id} is a composite foreign key to {@code fiscal_document (company_id, id)}
 *       ({@code V108}), so what the column stores is a row id and not a folio: the request's folio is looked up by
 *       {@code uuid_fiscal}, which is unique per company. A folio that resolves to nothing — another company's, a
 *       draft, or a value that names no document — is a <b>400</b> naming the field
 *       ({@link FiscalSubstitutingFolioUnknownException}) and nothing is sent. The one-way rule that motive
 *       {@code 01} <i>requires</i> a folio belongs to {@link CancellationRequest}, which states it with the
 *       attribute's own spelling.</li>
 *   <li><b>Nothing is written before the call.</b> The row is written in step 8 and nowhere earlier, so a refusal at
 *       any point — including the authority's — leaves a row set that is exactly what it was. That is
 *       {@code FiscalDeferredPaymentNotSupportedException}'s precedent ({@code L15}(d)): a refund that happens after
 *       a write leaves a row in a table that refuses {@code DELETE} ({@code V108}).</li>
 *   <li><b>The request is built from the frozen row and sealed.</b> {@code RfcEmisor} is the document's
 *       {@code emisor_rfc} and never the live company ({@code S9}), and {@code Fecha} is <b>one</b> {@link Clock}
 *       read in the configured fiscal zone, exactly as {@code FiscalDocumentStamper} does it — the two documents state
 *       one instant ({@code L19}(d), {@code L20}(g)). The seal is
 *       {@link CfdiCancellationSealer}'s: an enveloped XMLDSig over the seal-less rendering, because this request's
 *       signature is an element of the document and not the {@code Sello} a CFDI carries ({@code L20}(g)).</li>
 *   <li><b>The authority is asked once.</b> {@link FiscalCancellationProvider#cancel(FiscalCancellationRequest)}.</li>
 *   <li><b>Only a granted cancellation writes a row.</b> {@link FiscalCancellationResult.Cancelled} inserts the row
 *       and answers it. {@link FiscalCancellationResult.InProcess} writes <b>nothing</b> and answers with the
 *       authority's code, because a submitted request is not a cancelled document and
 *       {@code ck_fiscal_document_status} has no state for it — writing {@code CANCELLED} there is the lie
 *       {@code V108} exists to prevent ({@code L20}(d)). {@link FiscalCancellationResult.Rejected} writes nothing and
 *       raises {@link FiscalCancellationRejectedException}, the <b>409</b> the stamping path answers
 *       {@code FiscalStampRejectedException} with.</li>
 *   <li><b>With nothing registered the answer is a named refusal</b>, not a boot failure
 *       ({@link FiscalCancellationNotConfiguredException}), exactly as the stamper resolves its provider and sealer
 *       from an {@code ObjectProvider} ({@code L14}(e)).</li>
 * </ol>
 *
 * <p><b>What this class deliberately does not do.</b> It does not read a live catalog ({@code S9}), so a company that
 * renamed itself does not change the {@code RfcEmisor} of a cancellation of last year's document. It does not touch
 * the cancelled row or its artefact. It does not remember a submitted cancellation: the acceptance poll that would is
 * {@code L20}(i)'s and needs a state this schema does not have. And it posts nothing to the books ({@code S14}): the
 * revenue accrued at the sale and ADR-0008's projection describes the sale, not the document.</p>
 */
@Service
public class FiscalDocumentCancellationService {

    private static final Logger logger = LoggerFactory.getLogger(FiscalDocumentCancellationService.class);

    /** The only source kind this tree issues against ({@code ck_fiscal_document_source_type}, {@code V108}). */
    private static final String SOURCE_TYPE = FiscalDocumentSnapshotWriter.SOURCE_TYPE_SALES_ORDER;

    private final FiscalDocumentRepository fiscalDocumentRepository;
    private final FiscalDocumentArtifactRepository fiscalDocumentArtifactRepository;
    private final CurrentUserContext currentUserContext;

    /**
     * The port, asked for at call time and not at construction.
     *
     * <p>{@code FiscalProviderConfig} registers the vendor's adapter behind {@code app.fiscal.provider.enabled}, so an
     * installation that does not invoice holds no bean at all. Asking when a cancellation is attempted turns that
     * into one named refusal ({@link FiscalCancellationNotConfiguredException}) instead of an API that cannot start
     * for a capability nobody configured — the shape {@code FiscalDocumentStamper} already uses for the stamping
     * port.</p>
     */
    private final ObjectProvider<FiscalCancellationProvider> cancellationProvider;

    /**
     * The sealer, asked for at call time exactly as the provider is.
     *
     * <p>A cancellation request is an enveloped XMLDSig ({@code L20}(g)); an unsigned one is refused by the authority,
     * so a deployment that can send but not seal is one that cannot cancel — and the refusal names the half that is
     * missing instead of sending a document the SAT will reject.</p>
     */
    private final ObjectProvider<CfdiCancellationSealer> cancellationSealer;

    /**
     * The clock the request's {@code Fecha} is read from ({@code L12}(c), {@code FiscalConfig}).
     *
     * <p>The same bean the stamp reads, because the two documents state one instant: it is a {@link Clock} and not a
     * zone consulted per call so that a regression reading it twice is observable — {@code Fecha} has second
     * precision, and two reads inside one second would render the same string while signing a request over a
     * different value than the one sent ({@code L19}(d)).</p>
     */
    private final Clock fiscalClock;

    private final FiscalDocumentStoreScope storeScope;

    public FiscalDocumentCancellationService(
            FiscalDocumentRepository fiscalDocumentRepository,
            FiscalDocumentArtifactRepository fiscalDocumentArtifactRepository,
            CurrentUserContext currentUserContext,
            ObjectProvider<FiscalCancellationProvider> cancellationProvider,
            ObjectProvider<CfdiCancellationSealer> cancellationSealer,
            Clock fiscalClock,
            FiscalDocumentStoreScope storeScope) {
        this.fiscalDocumentRepository = fiscalDocumentRepository;
        this.fiscalDocumentArtifactRepository = fiscalDocumentArtifactRepository;
        this.currentUserContext = currentUserContext;
        this.cancellationProvider = cancellationProvider;
        this.cancellationSealer = cancellationSealer;
        this.fiscalClock = fiscalClock;
        this.storeScope = storeScope;
    }

    /**
     * Asks the authority to cancel one stamped document and records the answer.
     *
     * @param fiscalDocumentId the stamped document of the caller's company; a draft, an absent one, another
     *     company's and one without an artefact are all refused the same way
     * @param motive the authority's motive, resolved from the request's code by the boundary
     * @param substitutingFolio the fiscal UUID of the document that replaces this one, or {@code null} when the request
     *     states none; required by motive {@code 01} and accepted by the rest
     * @return the row written when the authority cancelled, or the authority's code when it has not decided yet
     * @throws FiscalDocumentNotStampedException when there is no stamped document to cancel in this company
     * @throws FiscalDocumentAlreadyCancelledException when a row already cancels it, raised before the authority is
     *     called
     * @throws com.lifecontrol.api.fiscal.exception.FiscalSubstitutingFolioRequiredException when the motive requires a
     *     folio and the request states none
     * @throws FiscalSubstitutingFolioUnknownException when the folio names no stamped document of this company
     * @throws FiscalCancellationNotConfiguredException when nothing answers the port or nothing can seal the request
     * @throws FiscalCancellationRejectedException when the authority refused, leaving nothing written
     */
    @Transactional
    public FiscalDocumentCancellation cancel(
            UUID fiscalDocumentId, FiscalCancellationMotive motive, UUID substitutingFolio) {
        Objects.requireNonNull(fiscalDocumentId, "a cancellation names the document it is about");
        Objects.requireNonNull(motive, "a cancellation states the authority's motive");

        var companyId = TenantScope.current()
                .map(TenantScope.Scope::companyId)
                .filter(Objects::nonNull)
                .orElseThrow(() -> new FiscalDocumentNotStampedException(fiscalDocumentId));

        var cancelled = requireStampedDocument(companyId, fiscalDocumentId);

        // The store the document's sale belongs to decides, first of all: a caller of store A receives a 403 for
        // store B's document, and receives it before this method reads whether it is already cancelled and long
        // before the authority is called — a cancellation is a statement to the receiver, not a local flag (S3 of
        // odd/tasks/fiscal-document-cross-store-authz.md).
        storeScope.verifyAccessOfDocument(cancelled);

        // A document is cancelled once, and the schema says so (uq_fiscal_document_cancels). The read is here
        // because the constraint would answer after the SAT had registered a second cancellation.
        fiscalDocumentRepository
                .findByCompanyIdAndCancelsFiscalDocumentId(companyId, cancelled.getId())
                .ifPresent(existing -> {
                    throw new FiscalDocumentAlreadyCancelledException(cancelled.getId(), existing.getId());
                });

        // The pointer is a row id, so the folio resolves to a row — and a folio that names no stamped document of
        // this company is a 400 that names the field, stated before anything is built or sent.
        var substituting = resolveSubstitutingDocument(companyId, substitutingFolio);
        var request = new CancellationRequest(
                cancelled.getUuidFiscal(), motive, substituting == null ? null : substituting.getUuidFiscal());

        var provider = cancellationProvider.getIfAvailable();
        if (provider == null) {
            throw FiscalCancellationNotConfiguredException.providerAbsent(cancelled.getId());
        }
        var sealer = cancellationSealer.getIfAvailable();
        if (sealer == null) {
            throw FiscalCancellationNotConfiguredException.sealerAbsent(cancelled.getId());
        }

        var answer = provider.cancel(
                new FiscalCancellationRequest(seal(request, cancelled, sealer), cancelled.getUuidFiscal()));

        if (answer instanceof FiscalCancellationResult.InProcess inProcess) {
            // Nothing is written: a submitted request is not a cancelled document, and the schema has no state for
            // one (L20(d)). The 202 the endpoint answers is this value's to justify, not this class's.
            logger.info(
                    "Fiscal cancellation in process: document={}, company={}, uuid={}, statusUUID={}",
                    cancelled.getId(),
                    companyId,
                    cancelled.getUuidFiscal(),
                    inProcess.statusUuid());
            return FiscalDocumentCancellation.submitted(
                    cancelled.getId(), motive.code(), inProcess.statusUuid(), inProcess.message());
        }

        if (answer instanceof FiscalCancellationResult.Rejected rejected) {
            // Nothing was written: the row below is the only write of this transaction, so a rejection leaves the row
            // set exactly as the request found it.
            logger.warn(
                    "Fiscal cancellation rejected: document={}, company={}, uuid={}, issues={}",
                    cancelled.getId(),
                    companyId,
                    cancelled.getUuidFiscal(),
                    rejected.issues().size());
            throw new FiscalCancellationRejectedException(cancelled.getId(), rejected.issues());
        }

        var granted = (FiscalCancellationResult.Cancelled) answer;
        return record(cancelled, motive, substituting, granted);
    }

    // ─── the reads ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The stamped document of this company, or the 404 that says there is nothing to cancel.
     *
     * <p>Three filters and one existence check, and all four are the same answer on purpose: only a document the
     * authority witnessed has a UUID to cancel, only a sale's document is what this capability is about, and a
     * stamped row that kept no artefact is a defect — the cancellation is the receipt of a document whose stamped
     * bytes this tree should be able to return, and {@code S10} is what makes the artefact that record.</p>
     */
    private FiscalDocument requireStampedDocument(UUID companyId, UUID fiscalDocumentId) {
        var document = fiscalDocumentRepository
                .findByCompanyIdAndId(companyId, fiscalDocumentId)
                .filter(candidate -> FiscalDocument.STATUS_STAMPED.equals(candidate.getStatus()))
                .filter(candidate -> SOURCE_TYPE.equals(candidate.getSourceType()))
                .orElseThrow(() -> new FiscalDocumentNotStampedException(fiscalDocumentId));

        if (fiscalDocumentArtifactRepository
                .findByCompanyIdAndFiscalDocumentId(companyId, document.getId())
                .isEmpty()) {
            throw new FiscalDocumentNotStampedException(fiscalDocumentId);
        }
        return document;
    }

    /**
     * The document the stated folio names, or {@code null} when the request states no folio.
     *
     * <p>The read is by {@code uuid_fiscal}, which {@code uq_fiscal_document_uuid_fiscal} makes unique, and it names
     * the company: a folio of another company resolves to nothing, which is the refusal — the composite foreign key
     * would refuse the row anyway, and only after the authority had been asked. {@code STAMPED} is a filter and not a
     * formality: a draft carries no UUID and a cancellation carries none of its own, so what this read can find is
     * already stamped; stating it keeps the rule readable where the pointer is decided.</p>
     */
    private FiscalDocument resolveSubstitutingDocument(UUID companyId, UUID substitutingFolio) {
        if (substitutingFolio == null) {
            return null;
        }
        var substituting = fiscalDocumentRepository
                .findByCompanyIdAndUuidFiscal(companyId, substitutingFolio)
                .filter(candidate -> FiscalDocument.STATUS_STAMPED.equals(candidate.getStatus()))
                .orElseThrow(() -> new FiscalSubstitutingFolioUnknownException(substitutingFolio));

        // The substituting document is the second document a cancellation names, and the rule is stated over
        // "a sale, or a document that belongs to one": a folio of another store is refused here, as the 403 a
        // foreign store always receives, instead of resolving to a success that tells the caller the folio exists
        // (S8 of odd/tasks/fiscal-document-cross-store-authz.md).
        storeScope.verifyAccessOfDocument(substituting);
        return substituting;
    }

    // ─── the request ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The sealed Anexo 20 request: rendered once without a signature, sealed, and rendered again with it.
     *
     * <p>Two renderings of one {@link CancellationRequest} and <b>one</b> instant, so the document that is digested
     * and the document that is sent differ in the {@code Signature} and in nothing else — the argument
     * {@code L18}(d) makes for the CFDI's {@code Sello} and {@code L20}(g) makes for this one. The seal covers the
     * <b>seal-less</b> bytes by construction: {@link CfdiCancellationSealer#seal(String)} receives them and nothing
     * else.</p>
     */
    private String seal(CancellationRequest request, FiscalDocument cancelled, CfdiCancellationSealer sealer) {
        var builder = new CfdiCancellationRequestBuilder();
        var fecha = LocalDateTime.now(fiscalClock);
        var unsigned = builder.build(request, cancelled.getEmisorRfc(), fecha);
        return builder.build(request, cancelled.getEmisorRfc(), fecha, sealer.seal(unsigned));
    }

    // ─── the row ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * Writes the cancellation row and answers it.
     *
     * <p>The row is the one {@code V108} describes: {@code CANCELLED}, naming the cancelled document, carrying the
     * motive and — when the motive replaces it — the row of the substituting document, with no {@code uuid_fiscal} of
     * its own and no lines ({@code S7}). Nothing is derived from a catalog here, which is what keeps {@code S9} true
     * one row further on.</p>
     */
    private FiscalDocumentCancellation record(
            FiscalDocument cancelled,
            FiscalCancellationMotive motive,
            FiscalDocument substituting,
            FiscalCancellationResult.Cancelled granted) {
        var row = FiscalDocument.cancellationOf(
                cancelled,
                motive,
                substituting == null ? null : substituting.getId(),
                FiscalDocumentSnapshotWriter.requireActor(currentUserContext));

        var written = fiscalDocumentRepository.saveAndFlush(row);
        logger.info(
                "Fiscal document cancelled: document={}, cancellation={}, company={}, uuid={}, motive={}, replaces={}, statusUUID={}",
                cancelled.getId(),
                written.getId(),
                cancelled.getCompanyId(),
                cancelled.getUuidFiscal(),
                motive.code(),
                substituting == null ? null : substituting.getId(),
                granted.statusUuid());

        return FiscalDocumentCancellation.written(
                written.getId(),
                cancelled.getId(),
                motive.code(),
                written.getStatus(),
                granted.statusUuid(),
                granted.message());
    }
}
