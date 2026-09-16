package com.lifecontrol.api.purchaseorder.event;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Published after a purchase order has been successfully persisted.
 * <p>
 * Carries the order's internal UUID, business order number, and the
 * supplier/store it belongs to. Consumers should read the full order from
 * the repository if they need more than these identifiers.
 */
public class PurchaseOrderCreatedEvent extends ApplicationEvent {

    private final UUID purchaseOrderId;
    private final String orderNumber;
    private final UUID supplierId;
    private final UUID companyStoreId;

    public PurchaseOrderCreatedEvent(
            Object source, UUID purchaseOrderId, String orderNumber, UUID supplierId, UUID companyStoreId) {
        super(source);
        this.purchaseOrderId = purchaseOrderId;
        this.orderNumber = orderNumber;
        this.supplierId = supplierId;
        this.companyStoreId = companyStoreId;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public UUID getCompanyStoreId() {
        return companyStoreId;
    }
}
