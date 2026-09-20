package com.lifecontrol.api.goodsreceipt.repository;

import com.lifecontrol.api.goodsreceipt.model.GoodsReceipt;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {

    /** Most recent first, mirroring the module search convention's unfiltered branch. */
    Page<GoodsReceipt> findByEnabledTrueOrderByReceivedAtDesc(Pageable pageable);

    /**
     * Free-text search over the receipt number and the order number of the purchase order it settles,
     * mirroring {@code PurchaseOrderRepository#findBySearchTerm}.
     */
    @Query("""
        SELECT gr FROM GoodsReceipt gr
        WHERE gr.enabled = true
          AND (LOWER(gr.receiptNumber) LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(gr.purchaseOrder.orderNumber) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY gr.receivedAt DESC
        """)
    Page<GoodsReceipt> findBySearchTerm(@Param("search") String search, Pageable pageable);

    /**
     * Number of receipts already registered for a purchase order. The next work unit builds the
     * per-order receipt number from it as {@code GR-{orderNumber}-{NN}}, where {@code NN} is
     * {@code count + 1} written with a minimum width of two zero-padded digits. The number is
     * generated inside the purchase-order lock (W2-D4/W2-D8) and the generator fails closed when the
     * result would exceed the {@code receipt_number} {@code VARCHAR(30)} column.
     *
     * <p>This is a COUNT and not a {@code MAX(receipt_number)} string lookup on purpose: the receipt
     * document is immutable (rows are never deleted or renumbered), so the number of rows is the
     * exact sequence position; and the suffix lives inside a zero-padded text key, so once it widens
     * past the padded width ordering the text no longer orders the sequence.</p>
     */
    long countByPurchaseOrderId(UUID purchaseOrderId);

    /**
     * One store-scoped page of receipts with no free-text filter (W2-D10 tenant scoping). The caller
     * selects this finder instead of passing a {@code null} search to the searched one: PostgreSQL
     * cannot type a null-bound {@code :search IS NULL} predicate, so the search/no-search choice is
     * made here as two plain finders, mirroring
     * {@code PurchaseOrderService#getAllPurchaseOrders} and the unscoped {@link
     * #findBySearchTerm(String, Pageable)} / {@link #findByEnabledTrueOrderByReceivedAtDesc(Pageable)}
     * pair. The ordering matches the searched variant: most recent first.
     */
    Page<GoodsReceipt> findByEnabledTrueAndCompanyStoreIdInOrderByReceivedAtDesc(
            Collection<UUID> storeIds, Pageable pageable);

    /**
     * One store-scoped page of receipts filtered by a free-text search over the receipt number and
     * the order number of the purchase order it settles (W2-D10 tenant scoping).
     *
     * <p>Call it only with a non-blank {@code search}: the predicate has no null guard on purpose,
     * so the parameter is always bound as text and the null-parameter typing hazard that broke the
     * previous single-finder form cannot occur. The unsearched case uses
     * {@link #findByEnabledTrueAndCompanyStoreIdInOrderByReceivedAtDesc(Collection, Pageable)}.</p>
     */
    @Query("""
        SELECT gr FROM GoodsReceipt gr
        WHERE gr.enabled = true
          AND gr.companyStore.id IN :storeIds
          AND (LOWER(gr.receiptNumber) LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(gr.purchaseOrder.orderNumber) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY gr.receivedAt DESC
        """)
    Page<GoodsReceipt> findByCompanyStoreIdInAndSearchTerm(
            @Param("storeIds") Collection<UUID> storeIds, @Param("search") String search, Pageable pageable);
}
