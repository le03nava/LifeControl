package com.lifecontrol.api.purchaseorder.repository;

import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    List<PurchaseOrder> findByEnabledTrue();

    Page<PurchaseOrder> findByEnabledTrue(Pageable pageable);

    Page<PurchaseOrder> findByEnabledTrueOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
        SELECT po FROM PurchaseOrder po
        WHERE po.enabled = true
          AND (LOWER(po.supplier.supplierName) LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(po.companyStore.storeName) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY po.createdAt DESC
        """)
    Page<PurchaseOrder> findBySearchTerm(@Param("search") String search, Pageable pageable);

    Optional<PurchaseOrder> findByOrderNumber(String orderNumber);

    boolean existsByOrderNumber(String orderNumber);

    Optional<PurchaseOrder> findTopByOrderNumberStartingWithOrderByOrderNumberDesc(String orderNumberPrefix);
}
