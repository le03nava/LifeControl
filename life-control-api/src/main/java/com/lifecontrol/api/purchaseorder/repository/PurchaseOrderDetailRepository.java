package com.lifecontrol.api.purchaseorder.repository;

import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetail;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PurchaseOrderDetailRepository extends JpaRepository<PurchaseOrderDetail, UUID> {

    List<PurchaseOrderDetail> findByPurchaseOrderId(UUID purchaseOrderId);

    List<PurchaseOrderDetail> findByPurchaseOrderIdAndEnabledTrue(UUID purchaseOrderId);

    /**
     * The one lookup a route that names both ids may use: the line is the order's line, or it does not exist
     * for that order at all. A bare {@code findById} followed by a comparison reads another order's row before
     * rejecting it, and the comparison is what the next route added forgets.
     *
     * @param id the line's id, from the path
     * @param purchaseOrderId the order's id, from the path segment that was supposed to scope it
     * @return the enabled line, or empty when it is not one of that order's lines
     */
    Optional<PurchaseOrderDetail> findByIdAndPurchaseOrderIdAndEnabledTrue(UUID id, UUID purchaseOrderId);
}
