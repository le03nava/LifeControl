package com.lifecontrol.api.fiscal.service;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.cancellation.CancellationRequest;
import com.lifecontrol.api.fiscal.cancellation.CfdiCancellationRequestBuilder;
import com.lifecontrol.api.fiscal.cancellation.CfdiCancellationSealer;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort.Outcome;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationReconciliationPort.Registration;
import com.lifecontrol.api.fiscal.cancellation.FiscalCancellationRequest;
import com.lifecontrol.api.fiscal.dto.FiscalCancellationReconciliation;
import com.lifecontrol.api.fiscal.exception.FiscalCancellationNotConfiguredException;
import com.lifecontrol.api.fiscal.exception.FiscalCancellationNotFoundException;
import com.lifecontrol.api.fiscal.exception.FiscalDocumentNotStampedException;
import com.lifecontrol.api.fiscal.model.FiscalCancellationMotive;
import com.lifecontrol.api.fiscal.model.FiscalDocument;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a submitted cancellation into the answer the authority gave it — {@code T3} of
 * {@code odd/tasks/fiscal-cancellation-reconciliation.md}, whose contract is {@code S4} and {@code S5} of the same
 * record read through decisions {@code L2}, {@code L3} and {@code L4}.
 *
 * <p><b>Why the row the pass reads is a cancellation row and not the document.</b> The submission {@code T2} writes a
 * {@code CANCELLATION_PENDING} row that names the document it cancels, carries the motive and the substituting row, and
 * keeps the authority's code — everything a pass needs, in one row, reached by
 * {@code (company, cancels_fiscal_document_id)}. The document the authority is asked about is the <b>pointed</b> one:
 * a cancellation row has no {@code uuid_fiscal} of its own ({@code L2}(c)), so the UUID travels through the pointer.</p>
 *
 * <p><b>Why the order of the five steps is the rule.</b></p>
 *
 * <ol>
 *   <li><b>The tenant comes from the scope</b> ({@link TenantScope}), exactly as the cancelling service reads it:
 *       absent, the answer is its own <b>404</b> — a request that names no company names no document either.</li>
 *   <li><b>The cancellation row is read by the pointer</b>, and none is a domain <b>404</b>
 *       ({@link FiscalCancellationNotFoundException}): the document has no submitted cancellation to resolve, and
 *       answering "nothing to do" would report a settled state nobody reached.</li>
 *   <li><b>The store scope is verified before anything is answered</b>, on the row — which copies the source pair of
 *       the document it cancels, so it reaches the same sale the cancelling service authorizes. Parity with
 *       {@code FiscalDocumentCancellationService} is the point: the same document cannot be cancellable by a caller who
 *       cannot reconcile it.</li>
 *   <li><b>A row that is already finished is answered from the row and asks nothing</b> ({@code S1}, {@code S6}): a
 *       {@code CANCELLED} row, or one whose {@code cancellation_settled_at} is written, is done, and a second run that
 *       asked again would be the repeated PAC query this unit exists to remove.</li>
 *   <li><b>Everything else is one question to the authority, and its answer decides what is written</b>
 *       ({@code S5}): an {@link Outcome.Accepted} moves the row to {@code CANCELLED} — the one transition
 *       {@code V113}'s guard admits for a pending row — writing the code, the sentence and the settle stamp; an
 *       {@link Outcome.Rejected} keeps the row pending and settles it where it stands, with the authority's code
 *       preserved; an {@link Outcome.StillPending} writes nothing at all; and an {@link Outcome.NoTransaction} means
 *       the authority holds no transaction, so the pass <b>registers</b> the request again and still writes nothing.</li>
 * </ol>
 *
 * <p><b>Why a refused registration writes nothing and still answers the code.</b> The authority refused the request —
 * an account failure, an invalid signature — so no row may claim a new answer it never gave, and the row set stays
 * exactly as it was. What the caller gets is the authority's own code in {@code authorityCode}, because that is the
 * only thing that says why the request has to change, and the state the row is still in
 * ({@code CANCELLATION_PENDING}): inventing another one would state a fact nobody observed ({@code S5}).</p>
 *
 * <p><b>Why registering again rebuilds the request instead of keeping it.</b> Nothing keeps the sealed bytes — a
 * request is not a row — so the pass rebuilds it the way the submission did: the same sealer, the motive the row
 * carries, the substituting document resolved from the row's id back to its folio, and <b>one</b> clock read for
 * {@code Fecha}, exactly as {@code FiscalDocumentCancellationService} does it ({@code L19}(d), {@code L20}(g)). The
 * subscription's own code stays on the row and no settle stamp is written: a registration is not an answer.</p>
 *
 * <p><b>What this class deliberately does not do.</b> It does not build a new row: the pending row <b>is</b> the
 * cancellation waiting ({@code L2}(b)), and settling it is an update its guard admits. It does not schedule itself
 * ({@code L5}): {@code fiscal_document} is armed and forced, so no platform thread can enumerate the tenants that hold
 * a pending cancellation, and the pass therefore runs inside a request's tenant scope; a scheduler is a later,
 * purely additive unit. It does not retry a rejection ({@code S9}), and it reads no live catalog — the request is
 * rebuilt from the frozen row.</p>
 */
@Service
public class FiscalCancellationReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(FiscalCancellationReconciliationService.class);

    private final FiscalDocumentRepository fiscalDocumentRepository;
    private final FiscalDocumentStoreScope storeScope;

    /**
     * The reconciling port, asked for at call time and not at construction.
     *
     * <p>{@code FiscalProviderConfig} registers the vendor's adapters behind {@code app.fiscal.provider.enabled}, so an
     * installation that does not invoice holds no bean at all. Asking when a reconciliation is attempted turns that
     * into one named refusal ({@link FiscalCancellationNotConfiguredException}) instead of an API that cannot start for
     * a capability nobody configured — the shape the cancelling service already uses for its own port.</p>
     */
    private final ObjectProvider<FiscalCancellationReconciliationPort> reconciliationPort;

    /**
     * The sealer, asked for only when a registration has to be built.
     *
     * <p>A status read needs no signature; re-registering a request does, and an unsigned one is refused by the
     * authority. Asking lazily keeps a deployment that can read but not seal able to settle what the authority already
     * answered, and refuses the one path that would send a document the SAT rejects — naming the missing half instead
     * of sending it.</p>
     */
    private final ObjectProvider<CfdiCancellationSealer> cancellationSealer;

    /** The clock the settle stamp and the rebuilt request's {@code Fecha} are read from ({@code FiscalConfig}). */
    private final Clock fiscalClock;

    public FiscalCancellationReconciliationService(
            FiscalDocumentRepository fiscalDocumentRepository,
            FiscalDocumentStoreScope storeScope,
            ObjectProvider<FiscalCancellationReconciliationPort> reconciliationPort,
            ObjectProvider<CfdiCancellationSealer> cancellationSealer,
            Clock fiscalClock) {
        this.fiscalDocumentRepository = fiscalDocumentRepository;
        this.storeScope = storeScope;
        this.reconciliationPort = reconciliationPort;
        this.cancellationSealer = cancellationSealer;
        this.fiscalClock = fiscalClock;
    }

    /**
     * Asks the authority about one submitted cancellation and settles the row with what it answered.
     *
     * @param cancelledFiscalDocumentId the stamped document whose cancellation was submitted
     * @return what the row says after the pass, what the authority said, and whether it is finished
     * @throws FiscalDocumentNotStampedException when the caller's scope names no company, which is the cancelling
     *     service's own answer for the same absence
     * @throws FiscalCancellationNotFoundException when this document has no submitted cancellation in this company
     * @throws FiscalCancellationNotConfiguredException when nothing answers the reconciling port, or nothing can seal
     *     the request a registration needs
     * @throws com.lifecontrol.api.fiscal.exception.FiscalStampProviderException when the authority could not be read
     *     at all — a transport failure or an answer that states no verdict
     */
    @Transactional
    public FiscalCancellationReconciliation reconcile(UUID cancelledFiscalDocumentId) {
        Objects.requireNonNull(
                cancelledFiscalDocumentId,
                "a reconciliation names the stamped document whose cancellation it resolves");

        var companyId = TenantScope.current()
                .map(TenantScope.Scope::companyId)
                .filter(Objects::nonNull)
                .orElseThrow(() -> new FiscalDocumentNotStampedException(cancelledFiscalDocumentId));

        var cancellation = fiscalDocumentRepository
                .findByCompanyIdAndCancelsFiscalDocumentId(companyId, cancelledFiscalDocumentId)
                .orElseThrow(() -> new FiscalCancellationNotFoundException(cancelledFiscalDocumentId));

        // The store the document's sale belongs to decides, first of all, exactly as it does for the submission: the
        // pending row copies the source pair of the document it cancels, so this resolves the same sale without a
        // second read (S3 of odd/tasks/fiscal-document-cross-store-authz.md).
        storeScope.verifyAccessOfDocument(cancellation);

        if (isSettled(cancellation)) {
            logger.info(
                    "Fiscal cancellation already settled: document={}, cancellation={}, company={}, status={}, code={}",
                    cancelledFiscalDocumentId,
                    cancellation.getId(),
                    companyId,
                    cancellation.getStatus(),
                    cancellation.getCancellationStatusUuid());
            return answerFrom(cancellation);
        }

        var cancelled = requirePointedDocument(companyId, cancelledFiscalDocumentId);

        var port = reconciliationPort.getIfAvailable();
        if (port == null) {
            throw FiscalCancellationNotConfiguredException.reconcilerAbsent(cancelledFiscalDocumentId);
        }

        var outcome = port.status(cancelled.getUuidFiscal());
        return switch (outcome) {
            case Outcome.Accepted accepted -> settleAccepted(cancelledFiscalDocumentId, cancellation, accepted);
            case Outcome.Rejected rejected -> settleRejected(cancelledFiscalDocumentId, cancellation, rejected);
            case Outcome.StillPending pending -> answerAsWaiting(cancellation, pending.statusUuid(), pending.message());
            case Outcome.NoTransaction noTransaction ->
                register(port, companyId, cancelledFiscalDocumentId, cancellation, cancelled);
        };
    }

    // ─── the answers the pass settles ───────────────────────────────────────────────────────────────

    /**
     * The authority accepted the cancellation: the pending row becomes {@code CANCELLED}, which is the one transition
     * {@code V113}'s guard admits for it ({@code L2}(e)).
     *
     * <p>The pointer, the motive and the substituting row are the submission's and stay untouched: this row still
     * cancels the same document, and it still carries no UUID of its own ({@code L2}(c)). What it gains is the
     * authority's code, its sentence and the settle stamp of this pass.</p>
     */
    private FiscalCancellationReconciliation settleAccepted(
            UUID cancelledFiscalDocumentId, FiscalDocument cancellation, Outcome.Accepted accepted) {
        cancellation.settleCancellationAccepted(
                accepted.statusUuid(), accepted.message(), OffsetDateTime.now(fiscalClock));
        var written = fiscalDocumentRepository.saveAndFlush(cancellation);
        logger.info(
                "Fiscal cancellation accepted: document={}, cancellation={}, company={}, code={}, acuse={}",
                cancelledFiscalDocumentId,
                written.getId(),
                written.getCompanyId(),
                accepted.statusUuid(),
                "present");
        return answerFrom(written);
    }

    /**
     * The authority rejected the cancellation: the row stays {@code CANCELLATION_PENDING} and is settled by the stamp.
     *
     * <p>Promoting it would state a fact the authority denied, and leaving it unsettled would make every later pass ask
     * again about an answer already given ({@code S5}, {@code L2}(d)). The code is kept exactly as the authority stated
     * it — {@code 213} or {@code 204} — which is what an operator quotes back.</p>
     */
    private FiscalCancellationReconciliation settleRejected(
            UUID cancelledFiscalDocumentId, FiscalDocument cancellation, Outcome.Rejected rejected) {
        cancellation.settleCancellationRejected(
                rejected.statusUuid(), rejected.message(), OffsetDateTime.now(fiscalClock));
        var written = fiscalDocumentRepository.saveAndFlush(cancellation);
        logger.info(
                "Fiscal cancellation rejected: document={}, cancellation={}, company={}, code={}",
                cancelledFiscalDocumentId,
                written.getId(),
                written.getCompanyId(),
                rejected.statusUuid());
        return answerFrom(written);
    }

    // ─── the answer that moves nothing ──────────────────────────────────────────────────────────────

    /**
     * A still-pending answer, an absent transaction and a refused registration: nothing is written ({@code S5}).
     *
     * <p>The answer quotes the code <b>this pass read</b> rather than the row's, because the row was not touched: it
     * still says what the submission heard, and reporting that as this pass's reading would state a fact nobody
     * observed. {@code settled} is false and the state is the row's, so a caller reads "still waiting, and here is
     * why" without any of it having been stored.</p>
     */
    private static FiscalCancellationReconciliation answerAsWaiting(
            FiscalDocument cancellation, String authorityCode, String message) {
        return new FiscalCancellationReconciliation(
                cancellation.getCancelsFiscalDocumentId(), cancellation.getStatus(), authorityCode, message, false);
    }

    /**
     * The authority holds no transaction for this UUID, so the pass registers the request again and writes nothing.
     *
     * <p>A registration is not an answer: nothing about the document changed, so no row may claim one. The next pass
     * polls the status again, which is the behaviour a pass that only registered has to leave behind.</p>
     */
    private FiscalCancellationReconciliation register(
            FiscalCancellationReconciliationPort port,
            UUID companyId,
            UUID cancelledFiscalDocumentId,
            FiscalDocument cancellation,
            FiscalDocument cancelled) {
        var registration = port.register(rebuiltRequest(companyId, cancelledFiscalDocumentId, cancellation, cancelled));
        return switch (registration) {
            case Registration.Registered registered -> {
                logger.info(
                        "Fiscal cancellation registered again: document={}, cancellation={}, company={}, status=registered",
                        cancelledFiscalDocumentId,
                        cancellation.getId(),
                        companyId);
                yield answerAsWaiting(cancellation, cancellation.getCancellationStatusUuid(), registered.message());
            }
            case Registration.Refused refused -> {
                logger.warn(
                        "Fiscal cancellation registration refused: document={}, cancellation={}, company={}, codes={}",
                        cancelledFiscalDocumentId,
                        cancellation.getId(),
                        companyId,
                        refused.issues().stream().map(issue -> issue.code()).collect(Collectors.joining(",")));
                yield answerAsWaiting(
                        cancellation,
                        refused.issues().get(0).code(),
                        refused.issues().stream()
                                .map(issue -> issue.message() == null ? issue.code() : issue.message())
                                .collect(Collectors.joining("; ")));
            }
        };
    }

    // ─── the request a registration re-sends ────────────────────────────────────────────────────────

    /**
     * The sealed Anexo 20 request rebuilt from the row, exactly as the submission built it.
     *
     * <p>Two renderings of one {@link CancellationRequest} and <b>one</b> clock read, so the document that is digested
     * and the document that is sent differ in the {@code Signature} and in nothing else — the argument
     * {@code L20}(g) makes for the submission. {@code RfcEmisor} is the pointed document's frozen
     * {@code emisor_rfc} and never the live company ({@code S9}), the motive is the row's, and a substituting row is
     * resolved back to its folio because {@code FolioSustitucion} is a UUID while the column is a row id.</p>
     */
    private FiscalCancellationRequest rebuiltRequest(
            UUID companyId, UUID cancelledFiscalDocumentId, FiscalDocument cancellation, FiscalDocument cancelled) {
        var sealer = cancellationSealer.getIfAvailable();
        if (sealer == null) {
            throw FiscalCancellationNotConfiguredException.sealerAbsent(cancelledFiscalDocumentId);
        }

        var motive = FiscalCancellationMotive.fromCode(cancellation.getCancellationMotive())
                .orElseThrow(() -> new IllegalStateException("Fiscal document " + cancellation.getId()
                        + " is " + cancellation.getStatus() + " and states motive '"
                        + cancellation.getCancellationMotive() + "', which is not one of the authority's four codes: a "
                        + "row this tree cannot render cannot be registered again."));
        var request =
                new CancellationRequest(cancelled.getUuidFiscal(), motive, substitutingFolio(companyId, cancellation));

        var builder = new CfdiCancellationRequestBuilder();
        var fecha = LocalDateTime.now(fiscalClock);
        var unsigned = builder.build(request, cancelled.getEmisorRfc(), fecha);
        return new FiscalCancellationRequest(
                builder.build(request, cancelled.getEmisorRfc(), fecha, sealer.seal(unsigned)),
                cancelled.getUuidFiscal());
    }

    /**
     * The folio of the substituting document, resolved from the row id the column holds.
     *
     * <p>The column is a composite foreign key to {@code fiscal_document (company_id, id)} ({@code V108}), so the row
     * is there by construction; a read that finds nothing means the pointer and the schema disagree, which is refused
     * rather than sent as a request with no substitution.</p>
     */
    private UUID substitutingFolio(UUID companyId, FiscalDocument cancellation) {
        var substitutingId = cancellation.getReplacesFiscalDocumentId();
        if (substitutingId == null) {
            return null;
        }
        return fiscalDocumentRepository
                .findByCompanyIdAndId(companyId, substitutingId)
                .map(FiscalDocument::getUuidFiscal)
                .orElseThrow(() -> new IllegalStateException("Fiscal document " + cancellation.getId()
                        + " substitutes row " + substitutingId + " of company " + companyId + ", and no such document "
                        + "is readable: a substitution whose folio cannot be resolved cannot be registered again."));
    }

    // ─── the reads ──────────────────────────────────────────────────────────────────────────────────

    /** Whether this pass has nothing to settle: a granted cancellation, or a rejection a pass already stamped. */
    private static boolean isSettled(FiscalDocument cancellation) {
        return FiscalDocument.STATUS_CANCELLED.equals(cancellation.getStatus())
                || cancellation.getCancellationSettledAt() != null;
    }

    /**
     * The document the row points at, which is where the UUID the authority is asked about lives.
     *
     * <p>It must still be {@code STAMPED}: a cancellation row exists only beside a stamped document, and a pointer at
     * anything else is a row set this tree cannot read — refused as the cancelling service refuses a document with
     * nothing to cancel, rather than asked about a UUID that names nothing.</p>
     */
    private FiscalDocument requirePointedDocument(UUID companyId, UUID cancelledFiscalDocumentId) {
        return fiscalDocumentRepository
                .findByCompanyIdAndId(companyId, cancelledFiscalDocumentId)
                .filter(candidate -> FiscalDocument.STATUS_STAMPED.equals(candidate.getStatus()))
                .orElseThrow(() -> new FiscalDocumentNotStampedException(cancelledFiscalDocumentId));
    }

    /** The row as this pass left it: its state, its code, its sentence, and whether it is finished. */
    private static FiscalCancellationReconciliation answerFrom(FiscalDocument cancellation) {
        return new FiscalCancellationReconciliation(
                cancellation.getCancelsFiscalDocumentId(),
                cancellation.getStatus(),
                cancellation.getCancellationStatusUuid(),
                cancellation.getCancellationMessage(),
                isSettled(cancellation));
    }
}
