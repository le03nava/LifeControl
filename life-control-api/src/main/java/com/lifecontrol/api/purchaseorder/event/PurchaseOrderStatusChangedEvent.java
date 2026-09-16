package com.lifecontrol.api.purchaseorder.event;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Published after a purchase order header status transition has been
 * successfully persisted. Carries the previous and new status names so
 * consumers can react to specific transitions without re-reading the order.
 */
public class PurchaseOrderStatusChangedEvent extends ApplicationEvent {

    private final UUID purchaseOrderId;
    private final String orderNumber;
    private final String fromStatus;
    private final String toStatus;

    public PurchaseOrderStatusChangedEvent(
            Object source, UUID purchaseOrderId, String orderNumber, String fromStatus, String toStatus) {
        super(source);
        this.purchaseOrderId = purchaseOrderId;
        this.orderNumber = orderNumber;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }
}
