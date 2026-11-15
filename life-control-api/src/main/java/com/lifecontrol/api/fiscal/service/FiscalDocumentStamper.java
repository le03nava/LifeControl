package com.lifecontrol.api.fiscal.service;

import com.lifecontrol.api.currency.model.Currency;
import com.lifecontrol.api.currency.repository.CurrencyRepository;
import com.lifecontrol.api.fiscal.CfdiXmlBuilder;
import com.lifecontrol.api.fiscal.FiscalStampProvider;
import com.lifecontrol.api.fiscal.FiscalStampRequest;
import com.lifecontrol.api.fiscal.FiscalStampResult;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentDraft;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentStamp;
import com.lifecontrol.api.fiscal.exception.FiscalSaleAlreadyStampedException;
import com.lifecontrol.api.fiscal.exception.FiscalSaleNotFoundException;
import com.lifecontrol.api.fiscal.exception.FiscalStampProviderNotConfiguredException;
import com.lifecontrol.api.fiscal.exception.FiscalStampRejectedException;
import com.lifecontrol.api.fiscal.exception.FiscalTaxMappingMissingException;
import com.lifecontrol.api.fiscal.model.FiscalDocument;
import com.lifecontrol.api.fiscal.model.FiscalDocumentArtifact;
import com.lifecontrol.api.fiscal.model.FiscalDocumentLine;
import com.lifecontrol.api.fiscal.model.IssuerSeal;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentArtifactRepository;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentLineRepository;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentRepository;
import com.lifecontrol.api.salesorder.model.SalesOrder;
import com.lifecontrol.api.salesorder.repository.SalesOrderRepository;
import com.lifecontrol.api.tax.repository.TaxCodeRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The idempotent stamp attempt: one sale, one attempt key, one call to the authority, and one artefact that is
 * exactly the bytes the authority returned ({@code S10} and {@code S11} of
 * {@code odd/tasks/cfdi-fiscal-document.md}), the second half of {@code T6}.
 *
 * <p><b>The order of the four steps is the design, and each one exists because the previous one cannot say what
 * it says.</b></p>
 *
 * <ol>
 *   <li><b>The replay comes first, and it costs nothing.</b> A document for this {@code (company, sale, attempt)}
 *       that is already {@code STAMPED} is returned as it stands and the provider is <b>never called</b>. The
 *       attempt key is unique per sale by {@code uq_fiscal_document_attempt} ({@code V110}), so the read answers
 *       at most one row even when two writers raced — the loser reads the winner's document
 *       ({@code L12}(h)).</li>
 *   <li><b>A sale that already carries a stamped document is refused <i>before</i> the network call.</b>
 *       {@code uq_fiscal_document_one_stamped} ({@code V108}) would refuse the second stamped row anyway, but by
 *       then the authority had stamped a document this transaction is about to lose. A stamp is the one thing
 *       that cannot be recomputed, so the guard is here and not in the constraint alone
 *       ({@link FiscalSaleAlreadyStampedException}).</li>
 *   <li><b>The draft comes from {@link FiscalDocumentSnapshotWriter} and is never re-derived here.</b> That
 *       class is the one place a live catalog is read and frozen ({@code S9}); duplicating any of its rules
 *       — the completed-sale check, the two fiscal profiles, the product and unit keys, the tax mapping — would
 *       be a second copy of the only code allowed to decide what a document states. A fresh attempt writes the
 *       draft; a replayed key returns the one it already wrote.</li>
 *   <li><b>The provider is called once, and both answers are terminal for what was written.</b> A stamped answer
 *       writes the artefact and transitions the document to {@code STAMPED} <b>in the same transaction</b>, so a
 *       committed {@code STAMPED} row always has its XML and a failed attempt leaves neither. A rejection raises
 *       {@link FiscalStampRejectedException} carrying the provider's codes and leaves the draft — the only
 *       mutable state ({@code L9}(b)) — exactly as it was.</li>
 * </ol>
 *
 * <p><b>Why the two catalog facts this class reads are read here and not by the builder.</b> The frozen row set
 * carries the tree's tax code and the document's currency, not the authority's {@code Impuesto} and not the
 * currency's scale; {@link CfdiXmlBuilder} is pure and receives both, so this class resolves them <b>once</b> per
 * stamp and the builder stays a function of the document. The reads are bounded to the codes the document's own
 * lines name, and the document's currency row, which is a catalog and not a tenant table.
 *
 * <p><b>Nothing here is exposed over HTTP.</b> The endpoint, the credentials and the provider adapter are
 * {@code T7}'s; this class is the transaction T7 will call.</p>
 */
@Service
public class FiscalDocumentStamper {

    private static final Logger logger = LoggerFactory.getLogger(FiscalDocumentStamper.class);

    /** The only source kind this tree issues against ({@code ck_fiscal_document_source_type}, {@code V108}). */
    private static final String SOURCE_TYPE = FiscalDocumentSnapshotWriter.SOURCE_TYPE_SALES_ORDER;

    /** {@code c_ObjetoImp} {@code 01}: not subject, so the concept states no tax node and needs no mapping. */
    private static final String TAX_OBJECT_NOT_SUBJECT = "01";

    /** The builder's column name in a refusal, so the message names the column and not a rule. */
    private static final String SAT_IMPUESTO_COLUMN = "sat_impuesto";

    private final SalesOrderRepository salesOrderRepository;
    private final FiscalDocumentRepository fiscalDocumentRepository;
    private final FiscalDocumentLineRepository fiscalDocumentLineRepository;
    private final FiscalDocumentArtifactRepository fiscalDocumentArtifactRepository;
    private final CurrencyRepository currencyRepository;
    private final TaxCodeRepository taxCodeRepository;
    private final FiscalDocumentSnapshotWriter snapshotWriter;

    /**
     * The port, asked for at call time and not at construction.
     *
     * <p>{@code T7} registers the vendor's adapter; until then no bean implements the port, and injecting it
     * directly would make every context of this API fail to start for a dependency the deployment has not
     * configured. Asking when a stamp is attempted turns that into one named refusal
     * ({@link FiscalStampProviderNotConfiguredException}) instead of an undeployable service.</p>
     */
    private final ObjectProvider<FiscalStampProvider> stampProvider;

    /** The zone the document's {@code Fecha} is rendered in ({@code L12}(c), {@code FiscalConfig}). */
    private final ZoneId fiscalZoneId;

    /**
     * Reads back the four header columns the schema states and this entity cannot write.
     *
     * <p>{@code tipo_comprobante}, {@code metodo_pago}, {@code forma_pago} and {@code exportacion} are
     * {@code insertable = false, updatable = false} — the schema's defaults write them ({@code V109}) — so the
     * instance the writer just inserted carries {@code null} in all four until something reads the row again.
     * Hibernate does not re-read a non-insertable column after an insert; the builder needs the database's values,
     * so this is the same {@code flush()} + {@code refresh()} the purchase order's derived money uses for the
     * same reason.</p>
     */
    private final EntityManager entityManager;

    public FiscalDocumentStamper(
            SalesOrderRepository salesOrderRepository,
            FiscalDocumentRepository fiscalDocumentRepository,
            FiscalDocumentLineRepository fiscalDocumentLineRepository,
            FiscalDocumentArtifactRepository fiscalDocumentArtifactRepository,
            CurrencyRepository currencyRepository,
            TaxCodeRepository taxCodeRepository,
            FiscalDocumentSnapshotWriter snapshotWriter,
            ObjectProvider<FiscalStampProvider> stampProvider,
            ZoneId fiscalZoneId,
            EntityManager entityManager) {
        this.salesOrderRepository = salesOrderRepository;
        this.fiscalDocumentRepository = fiscalDocumentRepository;
        this.fiscalDocumentLineRepository = fiscalDocumentLineRepository;
        this.fiscalDocumentArtifactRepository = fiscalDocumentArtifactRepository;
        this.currencyRepository = currencyRepository;
        this.taxCodeRepository = taxCodeRepository;
        this.snapshotWriter = snapshotWriter;
        this.stampProvider = stampProvider;
        this.fiscalZoneId = fiscalZoneId;
        this.entityManager = entityManager;
    }

    /**
     * Stamps one attempt of one sale, or answers with the document that attempt already stamped.
     *
     * @param salesOrderId the completed sale the document derives from; a sale of another company is not visible
     *     under the caller's tenant and is refused exactly like a sale that does not exist
     * @param attemptKey which stamp attempt this is; the same key for the same sale is the same attempt and never
     *     calls the authority twice
     * @param seal the certificate serial, the certificate and the seal {@code T7} produces
     * @return the stamped document and the bytes the provider returned
     * @throws com.lifecontrol.api.fiscal.exception.FiscalAttemptKeyRequiredException when the key is blank
     * @throws FiscalSaleNotFoundException when the sale does not exist or belongs to another company
     * @throws FiscalSaleAlreadyStampedException when the sale already has a live stamped document under another
     *     attempt key, raised before the provider is called
     * @throws FiscalStampRejectedException when the authority refused the document, leaving it {@code DRAFT}
     * @throws FiscalStampProviderNotConfiguredException when nothing implements the port in this deployment
     * @throws FiscalTaxMappingMissingException when a subject concept's tax code has no authority mapping
     */
    @Transactional
    public FiscalDocumentStamp stamp(UUID salesOrderId, String attemptKey, IssuerSeal seal) {
        Objects.requireNonNull(seal, "a stamp states the certificate and the seal that sign it");

        var sale = salesOrderRepository
                .findById(salesOrderId)
                .orElseThrow(() -> new FiscalSaleNotFoundException(salesOrderId));

        // Normalized the same way the snapshot writer normalizes it before writing, because the replay is a read
        // of that written value: a padded key that the writer trimmed would otherwise look like a second attempt
        // of the sale and be refused as one, which is the idempotency inverted.
        var key = attemptKey == null ? null : attemptKey.trim();
        var recorded = fiscalDocumentRepository.findByCompanyIdAndSourceTypeAndSourceIdAndAttemptKey(
                sale.getCompanyId(), SOURCE_TYPE, salesOrderId, key);
        if (recorded.isPresent()
                && FiscalDocument.STATUS_STAMPED.equals(recorded.get().getStatus())) {
            return replay(recorded.get());
        }

        requireNoOtherStamp(sale);

        // The draft is the writer's, never a second set of its rules: a fresh key writes one and a replayed key
        // answers with the draft it wrote before, so this call is where S11's "the same attempt never stamps
        // twice" is enforced one state before the stamp itself.
        var draft = snapshotWriter.createDraft(salesOrderId, key);
        return stampDraft(sale, draft, seal);
    }

    // ─── the stamp ─────────────────────────────────────────────────────────────────────────────────────

    private FiscalDocumentStamp stampDraft(SalesOrder sale, FiscalDocumentDraft draft, IssuerSeal seal) {
        var document = fiscalDocumentRepository
                .findById(draft.id())
                .orElseThrow(() -> new IllegalStateException("Attempt " + draft.attemptKey() + " of sale "
                        + sale.getId() + " answered document " + draft.id() + " and it cannot be read back."));
        refreshStatementFacts(document);
        var lines = fiscalDocumentLineRepository.findByFiscalDocumentIdOrderByCreatedAtAsc(document.getId());
        if (lines.isEmpty()) {
            throw new IllegalStateException("Fiscal document " + document.getId() + " states no concept, and a "
                    + "CFDI without concepts is a document the authority refuses. The snapshot writer reads the "
                    + "sale's enabled lines, so a sale with none has nothing to invoice.");
        }

        var provider = stampProvider.getIfAvailable();
        if (provider == null) {
            throw new FiscalStampProviderNotConfiguredException(document.getId());
        }

        var xml =
                builderFor(document.getCurrency(), lines).build(document, lines, seal, LocalDateTime.now(fiscalZoneId));
        var answer = provider.stamp(new FiscalStampRequest(xml, document.getEmisorRfc()));
        if (answer instanceof FiscalStampResult.Rejected rejected) {
            // Nothing was written: the artefact and the transition below are the only writes of this transaction,
            // so a rejection leaves the draft exactly as the attempt found it (S11).
            logger.warn(
                    "Fiscal stamp rejected: document={}, sale={}, attempt={}, issues={}",
                    document.getId(),
                    sale.getId(),
                    draft.attemptKey(),
                    rejected.issues().size());
            throw new FiscalStampRejectedException(document.getId(), sale.getId(), rejected.issues());
        }

        var stamped = (FiscalStampResult.Stamped) answer;
        keep(document, stamped);
        logger.info(
                "Fiscal document stamped: document={}, sale={}, attempt={}, uuid={}",
                document.getId(),
                sale.getId(),
                draft.attemptKey(),
                stamped.uuid());
        return describe(document, stamped.xml(), true);
    }

    /** Reads the four schema-stated header columns back onto the instance, which the insert could not set. */
    private void refreshStatementFacts(FiscalDocument document) {
        fiscalDocumentRepository.flush();
        entityManager.refresh(document);
    }

    /**
     * Keeps the provider's bytes and moves the document out of {@code DRAFT}, in this transaction.
     *
     * <p>The artefact is written first so the row set never contains a {@code STAMPED} document whose XML was
     * not kept, and the transition is the <b>one</b> legitimate {@code UPDATE} of {@code fiscal_document}
     * ({@code V108}, {@code L9}(b)). The trigger still protects a stamped row — any later update or delete of it
     * raises — so what this method opens is the transition and nothing else.</p>
     */
    private void keep(FiscalDocument document, FiscalStampResult.Stamped stamped) {
        fiscalDocumentArtifactRepository.save(
                new FiscalDocumentArtifact(document.getId(), document.getCompanyId(), stamped.xml()));
        document.setStatus(FiscalDocument.STATUS_STAMPED);
        document.setUuidFiscal(stamped.uuid());
        document.setStampedAt(stamped.stampedAt());
        fiscalDocumentRepository.saveAndFlush(document);
    }

    // ─── the replay and the guard ──────────────────────────────────────────────────────────────────────

    /**
     * The answer for an attempt that already stamped: the row and the artefact, and no provider call.
     *
     * <p>{@code xml} is {@code null} when the stamped row kept no artefact. The schema does not forbid that state
     * ({@code L9}(d) is a reading of the primary key, not a constraint), so a replay does not invent one, does
     * not re-stamp and does not fail: {@code S10} makes the <b>artefact</b> the authority of a reprint, and a
     * document without one is a defect the reprint path ({@code T8}) is where to answer.</p>
     */
    private FiscalDocumentStamp replay(FiscalDocument document) {
        var xml = fiscalDocumentArtifactRepository
                .findByCompanyIdAndFiscalDocumentId(document.getCompanyId(), document.getId())
                .map(FiscalDocumentArtifact::getXml)
                .orElse(null);
        logger.info(
                "Fiscal stamp replay: document={}, attempt={}, artefact={}",
                document.getId(),
                document.getAttemptKey(),
                xml != null);
        return describe(document, xml, false);
    }

    /**
     * Refuses a second, differently keyed attempt at a sale that is already invoiced, before calling anyone.
     *
     * <p>The replay above has already returned for its own key, so every stamped document found here belongs to
     * another attempt and cannot be this one.</p>
     */
    private void requireNoOtherStamp(SalesOrder sale) {
        fiscalDocumentRepository
                .findByCompanyIdAndSourceTypeAndSourceIdOrderByCreatedAtAsc(
                        sale.getCompanyId(), SOURCE_TYPE, sale.getId())
                .stream()
                .filter(candidate -> FiscalDocument.STATUS_STAMPED.equals(candidate.getStatus()))
                .findFirst()
                .ifPresent(stamped -> {
                    throw new FiscalSaleAlreadyStampedException(sale.getId(), stamped.getId(), stamped.getUuidFiscal());
                });
    }

    // ─── the builder's two inputs, read once ───────────────────────────────────────────────────────────

    /**
     * The builder for this document: the currency's decimals and the authority's {@code Impuesto} per code.
     *
     * <p>Both are read here, once, and handed to the pure builder — the reason is in its javadoc. The currency
     * row is guaranteed by {@code fiscal_document.currency}'s foreign key; a missing one is a broken schema and
     * not a caller's mistake, so it is an {@code IllegalStateException} and not a named refusal.</p>
     */
    private CfdiXmlBuilder builderFor(String currencyCode, List<FiscalDocumentLine> lines) {
        var minorUnits = currencyRepository
                .findById(currencyCode)
                .map(Currency::getMinorUnits)
                .orElseThrow(() -> new IllegalStateException("Fiscal document states currency " + currencyCode
                        + " and the currency catalog does not carry it, so its amounts have no scale to be "
                        + "rendered at."));
        return new CfdiXmlBuilder(minorUnits, satImpuestoByTaxCode(lines));
    }

    /**
     * The authority's {@code Impuesto} for each tax code the concepts actually name.
     *
     * <p>A not-subject concept is skipped because it states no tax node at all ({@code V110}); every other one
     * has to resolve, and a code the catalog does not carry or has not mapped is refused by name instead of
     * written as a guess ({@code S3}, ADR-0007 decision 11). The code's absence from the returned map is what
     * the builder refuses, so the column is named in exactly one place.</p>
     */
    private Map<String, String> satImpuestoByTaxCode(List<FiscalDocumentLine> lines) {
        Map<String, String> byCode = new LinkedHashMap<>();
        for (var line : lines) {
            if (TAX_OBJECT_NOT_SUBJECT.equals(line.getTaxObject()) || byCode.containsKey(line.getTaxCode())) {
                continue;
            }
            var taxCode = taxCodeRepository
                    .findById(line.getTaxCode())
                    .orElseThrow(() -> FiscalTaxMappingMissingException.ofUnknownCode(
                            line.getSalesOrderItemId(), line.getTaxCode()));
            if (StringUtils.hasText(taxCode.getSatImpuesto())) {
                byCode.put(taxCode.getCode(), taxCode.getSatImpuesto());
            }
        }
        return byCode;
    }

    // ─── the answer ───────────────────────────────────────────────────────────────────────────────────

    private FiscalDocumentStamp describe(FiscalDocument document, String xml, boolean now) {
        return new FiscalDocumentStamp(
                document.getId(),
                document.getCompanyId(),
                document.getSourceId(),
                document.getAttemptKey(),
                document.getStatus(),
                document.getUuidFiscal(),
                document.getStampedAt(),
                xml,
                now);
    }
}
