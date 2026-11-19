package com.lifecontrol.api.fiscal.service;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentRead;
import com.lifecontrol.api.fiscal.exception.FiscalSaleNotFoundException;
import com.lifecontrol.api.fiscal.model.FiscalDocument;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentRepository;
import com.lifecontrol.api.salesorder.repository.SalesOrderRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read of one sale's fiscal document ({@code S1}–{@code S5} and {@code S7} of
 * {@code odd/tasks/fiscal-document-read.md}): what the sale produced, by the sale alone.
 *
 * <p><b>Why it adds no query.</b> The two finders this read needs already exist ({@code L3}):
 * {@link FiscalDocumentRepository#findByCompanyIdAndSourceTypeAndSourceIdOrderByCreatedAtAsc} gives every row a
 * sale ever produced and {@link FiscalDocumentRepository#findByCompanyIdAndCancelsFiscalDocumentId} gives the
 * cancellation copy that names one. The service picks the {@code STAMPED} row and asks the second finder about it;
 * there is nothing left for a new method to say.</p>
 *
 * <p><b>Why "no document" is an empty answer and "no sale" is a refusal.</b> The two are different facts and a
 * client renders them differently ({@code L6}): a completed sale that was never invoiced is a state — the state in
 * which a screen offers the issue action — and answers {@code 204}, while a sale that does not exist and a sale of
 * another company are indistinguishable and answer {@code 404} ({@code S5}). The sale read is what makes the
 * difference possible: without it every unknown id would look like a sale that was never invoiced.</p>
 *
 * <p><b>Why a cancelled document still answers its stamped row.</b> {@code V108}'s append-only trigger refuses
 * every {@code UPDATE} of a {@code STAMPED} row, so a cancellation <b>inserts</b> a {@code CANCELLED} copy naming
 * it ({@code S3}). The sale therefore still has its stamped document, and the copy travels in the cancellation
 * block. A reader that looked only for {@code status = 'CANCELLED'} would answer "no document" to a sale the
 * authority did cancel, and one that reported the copy as the document would hand a reprint the wrong id.</p>
 *
 * <p><b>Why the tenant comes from the scope and not from the path.</b> The endpoint names no company
 * ({@code /api/fiscal-documents/by-sale/{salesOrderId}}), so {@code TenantScopeFilter} states the caller's single
 * {@code company_id} claim as the operation's tenant before any controller runs, exactly as it does for the
 * reprint. Both finders then name that company explicitly, which makes "not found" a fact of the query and not a
 * property of whichever pooled connection served it ({@code S7}).</p>
 */
@Service
public class FiscalDocumentReadService {

    private static final Logger logger = LoggerFactory.getLogger(FiscalDocumentReadService.class);

    private final SalesOrderRepository salesOrderRepository;
    private final FiscalDocumentRepository fiscalDocumentRepository;
    private final FiscalDocumentStoreScope storeScope;

    public FiscalDocumentReadService(
            SalesOrderRepository salesOrderRepository,
            FiscalDocumentRepository fiscalDocumentRepository,
            FiscalDocumentStoreScope storeScope) {
        this.salesOrderRepository = salesOrderRepository;
        this.fiscalDocumentRepository = fiscalDocumentRepository;
        this.storeScope = storeScope;
    }

    /**
     * One sale's stamped document, if it has one.
     *
     * @param salesOrderId the sale whose document is read
     * @return the document the sale produced, or empty when nothing about it was ever stamped — a sale with no
     *     attempt at all, with only drafts, or with only refused attempts
     * @throws FiscalSaleNotFoundException when the sale does not exist in the caller's company, which covers both an
     *     absent sale and another company's ({@code S5})
     */
    @Transactional(readOnly = true)
    public Optional<FiscalDocumentRead> readBySale(UUID salesOrderId) {
        Objects.requireNonNull(salesOrderId, "a read names the sale it is about");

        // A caller with no tenant reads no sale: the 404 is the honest answer, and it is the same one a sale of
        // another company receives, so the two cannot be told apart (S5).
        var companyId = TenantScope.current()
                .map(TenantScope.Scope::companyId)
                .filter(Objects::nonNull)
                .orElseThrow(() -> new FiscalSaleNotFoundException(salesOrderId));

        // The sale is read before the documents, because "never invoiced" and "no such sale" are different answers
        // and only the row can tell them apart. The policy already hides another company's sale; the comparison
        // states the same fact in the call, so a connection that bypassed the policy cannot turn a foreign sale into
        // a 204 (S5, S7).
        var sale = salesOrderRepository
                .findById(salesOrderId)
                .filter(candidate -> companyId.equals(candidate.getCompanyId()))
                .orElseThrow(() -> new FiscalSaleNotFoundException(salesOrderId));

        // The store the sale belongs to decides, before any document is read: a caller of store A receives a 403 for
        // store B's sale and learns nothing from it — not the authority's UUID, not the folio, not even whether one
        // exists (S1 of odd/tasks/fiscal-document-cross-store-authz.md). The tenant policy admits both stores because
        // both are of one company, so the store is the only axis that can refuse this.
        storeScope.verifyAccessOfSale(sale);

        var stamped = fiscalDocumentRepository
                .findByCompanyIdAndSourceTypeAndSourceIdOrderByCreatedAtAsc(
                        companyId, FiscalDocumentSnapshotWriter.SOURCE_TYPE_SALES_ORDER, salesOrderId)
                .stream()
                .filter(document -> FiscalDocument.STATUS_STAMPED.equals(document.getStatus()))
                .findFirst();

        // Nothing was stamped: not yet invoiced is a state, not a failure (S2). A draft, a refused attempt and a
        // cancellation-without-a-stamped-row all land here, and none of them is a document the authority witnessed.
        if (stamped.isEmpty()) {
            return Optional.empty();
        }

        var document = stamped.get();
        var cancellation = fiscalDocumentRepository
                .findByCompanyIdAndCancelsFiscalDocumentId(companyId, document.getId())
                .map(FiscalDocumentReadService::asCancellation);

        logger.info(
                "Fiscal document read by sale: sale={}, document={}, company={}, uuid={}, cancelled={}",
                salesOrderId,
                document.getId(),
                companyId,
                document.getUuidFiscal(),
                cancellation.isPresent());

        return Optional.of(new FiscalDocumentRead(
                document.getId(),
                document.getSourceId(),
                document.getStatus(),
                document.getUuidFiscal(),
                document.getStampedAt(),
                document.getFolio(),
                cancellation.orElse(null)));
    }

    /**
     * The cancellation block of one cancellation row.
     *
     * <p><b>Why nothing is read back here.</b> {@code created_at} is the schema's own {@code now()} and the column is
     * {@code insertable = false}, so an instance the <b>same</b> transaction inserted never carried it. In production
     * that cannot be observed: the read is one request and one transaction, and a cancellation is written by an
     * earlier one. The caller that writes the row is the caller that must flush it before asking this read, which is
     * what the suite does ({@code L10}); a refresh from this method would be both unreachable and dangerous, because
     * a {@code readOnly} read that joined an outer write transaction would silently discard its pending changes.</p>
     */
    private static FiscalDocumentRead.Cancellation asCancellation(FiscalDocument row) {
        return new FiscalDocumentRead.Cancellation(row.getId(), row.getCancellationMotive(), row.getCreatedAt());
    }
}
