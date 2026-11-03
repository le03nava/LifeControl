package com.lifecontrol.api.salesorder.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sales_order_items")
public class SalesOrderItem extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "sales_order_id", nullable = false)
    private UUID salesOrderId;

    @Column(name = "product_variant_id", nullable = false)
    private UUID productVariantId;

    @Column(name = "quantity", precision = 12, scale = 2, nullable = false)
    private BigDecimal quantity;

    @Column(name = "list_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal listPrice;

    @Column(name = "discount_applied", precision = 12, scale = 2)
    private BigDecimal discountApplied = BigDecimal.ZERO;

    @Column(name = "final_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal finalPrice;

    @Column(name = "promotion_id")
    private UUID promotionId;

    @Column(name = "status_id", nullable = false)
    private UUID statusId;

    /**
     * The line's typed state (ADR-0004 decision 3), the reason "an item of a charged order cannot be
     * cancelled" stops being a {@code !"Cancelled".equals(…)} test and becomes the absence of an edge out of
     * {@link SalesOrderItemState#ADDED}.
     *
     * <p>Read by the service to classify a line — a {@code CANCELLED} line holds no stock — so that
     * classification cannot fail on a catalog lookup and never falls back to a guess (the defect
     * {@code odd/tasks/sales-location-aware-stock.md} records as F-2). Not marked {@code nullable = false}
     * for the reason {@link SalesOrder#getStatus()} gives.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24)
    private SalesOrderItemState status;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public SalesOrderItem() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public UUID getSalesOrderId() {
        return salesOrderId;
    }

    public UUID getProductVariantId() {
        return productVariantId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getListPrice() {
        return listPrice;
    }

    public BigDecimal getDiscountApplied() {
        return discountApplied;
    }

    public BigDecimal getFinalPrice() {
        return finalPrice;
    }

    public UUID getPromotionId() {
        return promotionId;
    }

    public UUID getStatusId() {
        return statusId;
    }

    public SalesOrderItemState getStatus() {
        return status;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setSalesOrderId(UUID salesOrderId) {
        this.salesOrderId = salesOrderId;
    }

    public void setProductVariantId(UUID productVariantId) {
        this.productVariantId = productVariantId;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public void setListPrice(BigDecimal listPrice) {
        this.listPrice = listPrice;
    }

    public void setDiscountApplied(BigDecimal discountApplied) {
        this.discountApplied = discountApplied;
    }

    public void setFinalPrice(BigDecimal finalPrice) {
        this.finalPrice = finalPrice;
    }

    public void setPromotionId(UUID promotionId) {
        this.promotionId = promotionId;
    }

    public void setStatusId(UUID statusId) {
        this.statusId = statusId;
    }

    public void setStatus(SalesOrderItemState status) {
        this.status = status;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final SalesOrderItem item = new SalesOrderItem();

        public Builder id(UUID id) {
            item.id = id;
            return this;
        }

        public Builder salesOrderId(UUID salesOrderId) {
            item.salesOrderId = salesOrderId;
            return this;
        }

        public Builder productVariantId(UUID productVariantId) {
            item.productVariantId = productVariantId;
            return this;
        }

        public Builder quantity(BigDecimal quantity) {
            item.quantity = quantity;
            return this;
        }

        public Builder listPrice(BigDecimal listPrice) {
            item.listPrice = listPrice;
            return this;
        }

        public Builder discountApplied(BigDecimal discountApplied) {
            item.discountApplied = discountApplied;
            return this;
        }

        public Builder finalPrice(BigDecimal finalPrice) {
            item.finalPrice = finalPrice;
            return this;
        }

        public Builder promotionId(UUID promotionId) {
            item.promotionId = promotionId;
            return this;
        }

        public Builder statusId(UUID statusId) {
            item.statusId = statusId;
            return this;
        }

        public Builder status(SalesOrderItemState status) {
            item.status = status;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            item.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            item.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            item.setUpdatedAt(updatedAt);
            return this;
        }

        public SalesOrderItem build() {
            return item;
        }
    }
}
