package com.lifecontrol.api.purchaseorder.event;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Published after a purchase order line's status transition has been
 * successfully persisted. Carries the receiving quantities so a future
 * goods-receipt consumer can apply the inventory/stock impact.
 */
public class PurchaseOrderDetailStatusChangedEvent extends ApplicationEvent {

    private final UUID purchaseOrderId;
    private final String orderNumber;
    private final UUID detailId;
    private final UUID productId;
    private final String fromStatus;
    private final String toStatus;
    private final BigDecimal receivedQuantity;
    private final BigDecimal orderedQuantity;

    public PurchaseOrderDetailStatusChangedEvent(
            Object source,
            UUID purchaseOrderId,
            String orderNumber,
            UUID detailId,
            UUID productId,
            String fromStatus,
            String toStatus,
            BigDecimal receivedQuantity,
            BigDecimal orderedQuantity) {
        super(source);
        this.purchaseOrderId = purchaseOrderId;
        this.orderNumber = orderNumber;
        this.detailId = detailId;
        this.productId = productId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.receivedQuantity = receivedQuantity;
        this.orderedQuantity = orderedQuantity;
    }

    public UUID getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public UUID getDetailId() {
        return detailId;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public BigDecimal getReceivedQuantity() {
        return receivedQuantity;
    }

    public BigDecimal getOrderedQuantity() {
        return orderedQuantity;
    }
}
