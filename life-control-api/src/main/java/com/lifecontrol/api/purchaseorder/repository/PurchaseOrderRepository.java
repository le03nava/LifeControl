package com.lifecontrol.api.purchaseorder.repository;

import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    List<PurchaseOrder> findByEnabledTrue();

    Page<PurchaseOrder> findByEnabledTrue(Pageable pageable);

    @Override
    @EntityGraph(value = "PurchaseOrder.withHierarchy", type = EntityGraph.EntityGraphType.FETCH)
    Optional<PurchaseOrder> findById(UUID id);

    @EntityGraph(value = "PurchaseOrder.withHierarchy", type = EntityGraph.EntityGraphType.FETCH)
    Page<PurchaseOrder> findByEnabledTrueOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
        SELECT po FROM PurchaseOrder po
        LEFT JOIN FETCH po.supplier
        LEFT JOIN FETCH po.companyStore cs
        LEFT JOIN FETCH cs.companyZone cz
        LEFT JOIN FETCH cz.companyRegion cr
        LEFT JOIN FETCH cr.companyCountry cc
        LEFT JOIN FETCH cc.company c
        LEFT JOIN FETCH po.paymentMethod
        LEFT JOIN FETCH po.status
        WHERE po.enabled = true
          AND (LOWER(po.supplier.supplierName) LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(po.companyStore.storeName) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY po.createdAt DESC
        """)
    Page<PurchaseOrder> findBySearchTerm(@Param("search") String search, Pageable pageable);

    Optional<PurchaseOrder> findByOrderNumber(String orderNumber);

    boolean existsByOrderNumber(String orderNumber);

    Optional<PurchaseOrder> findTopByOrderNumberStartingWithOrderByOrderNumberDesc(String orderNumberPrefix);

    /**
     * Pessimistic write lock on the purchase order. The goods receipt takes this lock before it
     * numbers the receipt and applies the reception, so two concurrent receptions against the same
     * order queue instead of racing the per-order receipt counter. It is the first lock of the
     * documented {@code purchaseOrder -> storeStock -> locationBalance} order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT po FROM PurchaseOrder po WHERE po.id = :id")
    Optional<PurchaseOrder> findByIdForUpdate(@Param("id") UUID id);
}
