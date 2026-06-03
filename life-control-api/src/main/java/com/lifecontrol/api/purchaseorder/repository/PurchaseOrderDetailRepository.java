package com.lifecontrol.api.purchaseorder.repository;

import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PurchaseOrderDetailRepository extends JpaRepository<PurchaseOrderDetail, UUID> {

    List<PurchaseOrderDetail> findByPurchaseOrderId(UUID purchaseOrderId);

    List<PurchaseOrderDetail> findByPurchaseOrderIdAndEnabledTrue(UUID purchaseOrderId);
}
