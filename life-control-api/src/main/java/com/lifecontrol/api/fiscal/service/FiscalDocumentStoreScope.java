package com.lifecontrol.api.fiscal.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.fiscal.model.FiscalDocument;
import com.lifecontrol.api.salesorder.model.SalesOrder;
import com.lifecontrol.api.salesorder.repository.SalesOrderRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.service.CompanyStoreOwnerResolver;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Authorizes the caller for the store a fiscal document belongs to ({@code S1}–{@code S8} of
 * {@code odd/tasks/fiscal-document-cross-store-authz.md}, issue {@code #967}).
 *
 * <p><b>Why a component and not four checks.</b> The four endpoints of this capability — read by sale, reprint,
 * cancel, issue — address a document in three different ways (by sale, by document id, by the substituting folio
 * a cancellation names) and every one of them has to answer the same question: <em>may this caller act on the
 * store this document came from?</em> Four copies of the walk that answers it are four chances to walk it
 * differently, and the defect this class closes is exactly the one a missing copy leaves: the whole surface was
 * authorized at company level, which is the tenant policy and not the caller's scope.</p>
 *
 * <p><b>Why the store is reached through the sale.</b> {@code fiscal_document} carries
 * {@code source_type}/{@code source_id} and no store of its own ({@code V108}), because the sale is the fact and
 * the document is what the sale produced. So the address is document &rarr; sale &rarr;
 * {@code sales_orders.company_store_id} &rarr; store, and the company &rarr; country &rarr; region &rarr; zone
 * &rarr; store chain is derived from the store row by {@link CompanyStoreOwnerResolver} — the same two steps
 * {@code SalesOrderService#verifyStoreAccessOf} performs for an existing order, and the same one rule
 * ({@link CurrentUserContext#verifyCompanyStoreAccess}), not a second statement of it. {@code lc-admin} is exempt
 * inside that call ({@code S7}), so this class states no exemption of its own.</p>
 *
 * <p><b>Why it fails closed.</b> A document whose sale cannot be read is refused with the same {@code 403} as one
 * belonging to a foreign store, never allowed through: the guard's job is to authorize an entity it can resolve,
 * and "I could not find the row that says which store this is" is not a grant. The row is unreachable in practice
 * — a sale is soft-deleted, never deleted — which is why the refusal is stated here instead of being reported as
 * a 404 somewhere downstream.</p>
 *
 * <p><b>Why the cost is accepted.</b> One extra select per id-addressed request, the same price {@code #889}
 * documented for the sales order, paid because the chain has one source instead of one copy per caller.</p>
 */
@Component
public class FiscalDocumentStoreScope {

    private final SalesOrderRepository salesOrderRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CompanyStoreOwnerResolver companyStoreOwnerResolver;
    private final CurrentUserContext currentUserContext;

    public FiscalDocumentStoreScope(
            SalesOrderRepository salesOrderRepository,
            CompanyStoreRepository companyStoreRepository,
            CompanyStoreOwnerResolver companyStoreOwnerResolver,
            CurrentUserContext currentUserContext) {
        this.salesOrderRepository = salesOrderRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.companyStoreOwnerResolver = companyStoreOwnerResolver;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Authorizes the caller for the store of a sale it already holds.
     *
     * <p>The read by sale and the issue path both resolve the sale before they need anything else, so they hand it
     * over instead of paying a second read of the same row.</p>
     *
     * @param sale the sale behind the document, already loaded
     * @throws AccessDeniedException when the caller holds no grant for the store's scope, which the handler states
     *     as a {@code 403}
     */
    public void verifyAccessOfSale(SalesOrder sale) {
        Objects.requireNonNull(sale, "a fiscal document belongs to a sale");
        verifyAccessOfStore(sale.getCompanyStoreId());
    }

    /**
     * Authorizes the caller for the store of the sale a document came from.
     *
     * @param document the document the caller addressed by its own id
     * @throws AccessDeniedException when that sale's store is outside the caller's store scope, or when the sale
     *     itself cannot be read
     */
    public void verifyAccessOfDocument(FiscalDocument document) {
        Objects.requireNonNull(document, "a refusal names the document it is about");

        // Only a sale's document is what this capability serves: the reprint and the cancellation already refuse
        // any other source kind as a 404 before this runs, and a future source kind must state its own rule rather
        // than inherit this one.
        if (!FiscalDocumentSnapshotWriter.SOURCE_TYPE_SALES_ORDER.equals(document.getSourceType())) {
            throw new AccessDeniedException("A fiscal document of this source kind is not addressable by store");
        }

        var sale = salesOrderRepository
                .findById(document.getSourceId())
                .orElseThrow(() -> new AccessDeniedException(
                        "The sale this fiscal document came from is not readable, so its store cannot be verified"));

        verifyAccessOfSale(sale);
    }

    /**
     * The two steps every caller shares: resolve the store row, then hand its whole chain to the guard.
     *
     * @throws CompanyStoreNotFoundException when the sale names a store that no longer exists, which the foreign
     *     key on {@code sales_orders.company_store_id} makes unreachable and which is refused rather than assumed
     */
    private void verifyAccessOfStore(UUID companyStoreId) {
        var store = companyStoreRepository
                .findById(companyStoreId)
                .orElseThrow(() -> new CompanyStoreNotFoundException(companyStoreId));
        var zone = store.getCompanyZone();
        var region = zone.getCompanyRegion();
        var country = region.getCompanyCountry();

        currentUserContext.verifyCompanyStoreAccess(
                companyStoreOwnerResolver.companyIdOf(store),
                country.getId(),
                region.getId(),
                zone.getId(),
                store.getId());
    }
}
