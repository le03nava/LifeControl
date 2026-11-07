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

    // ADR-0002 decision 1: `V81` moves the four line amounts to `NUMERIC(19,4)`, because two decimals
    // cannot hold a per-gram price or the quantity a unit price implies. The mapping states the same
    // shape: `ddl-auto=validate` compares them, and a widened column against a two-decimal mapping fails
    // every integration test at boot instead of one assertion.
    @Column(name = "quantity", precision = 19, scale = 4, nullable = false)
    private BigDecimal quantity;

    @Column(name = "list_price", precision = 19, scale = 4, nullable = false)
    private BigDecimal listPrice;

    // `V81` backfills the `NULL`s to 0 before the `NOT NULL` lands: a `NULL` here makes the line's checks
    // pass as `NULL` instead of refusing, which is the one way a breakdown is bypassed without an error.
    @Column(name = "discount_applied", precision = 19, scale = 4, nullable = false)
    private BigDecimal discountApplied = BigDecimal.ZERO;

    /**
     * The line's total as `V85` derives it: `round(list_price * quantity, 4) - discount_applied + tax_amount`,
     * by the trigger `trg_sales_order_items_final_price`, never by this mapping.
     *
     * <p><b>Why read-only.</b> The tree wrote the <b>unit</b> net price here until `V85` —
     * `list_price - discount_applied` — which is a different number on every line whose quantity is not 1 (D-f).
     * A mapped value would also be the value the row keeps on any statement the trigger's column list does not
     * name, exactly the stale-mapping trap {@link #subtotal} records. Mapping it
     * `insertable = false, updatable = false` says what is true: the schema is the writer, and the application
     * is the reader — which is why the service refreshes the row before it reports the line.</p>
     */
    @Column(name = "final_price", precision = 19, scale = 4, nullable = false, insertable = false, updatable = false)
    private BigDecimal finalPrice;

    /**
     * The line's money as `V81` derives it: `round(list_price * quantity, 4)`, by the trigger
     * `trg_sales_order_items_subtotal`, never by this mapping.
     *
     * <p><b>Why read-only.</b> Hibernate would insert the field's default and, worse, write it back on any
     * update that does not name `list_price` or `quantity` — and the trigger only fires "OF list_price,
     * quantity", so a stale mapped value would be the value that lands, disagreeing with the two numbers it
     * is made of and refused by `ck_item_subtotal`. Mapping it `insertable = false, updatable = false` says
     * what is true: the schema is the writer, and the application is the reader.</p>
     */
    @Column(name = "subtotal", precision = 19, scale = 4, insertable = false, updatable = false)
    private BigDecimal subtotal;

    /** The catalog code the rate was resolved from (ADR-0002 decision 6); `NA` until a rate exists. */
    @Column(name = "tax_code", length = 20, insertable = false, updatable = false)
    private String taxCode;

    /** The rate as it stood at write time: a snapshot, so a later `tax_rate` row never restates history. */
    @Column(name = "tax_rate", precision = 9, scale = 6, insertable = false, updatable = false)
    private BigDecimal taxRate;

    /** `round((subtotal - discount_applied) * tax_rate, 4)`, held by `ck_item_tax`. */
    @Column(name = "tax_amount", precision = 19, scale = 4, insertable = false, updatable = false)
    private BigDecimal taxAmount;

    @Column(name = "promotion_id")
    private UUID promotionId;

    /**
     * The line's typed state (ADR-0004 decision 3), the reason "an item of a charged order cannot be
     * cancelled" stops being a {@code !"Cancelled".equals(…)} test and becomes the absence of an edge out of
     * {@link SalesOrderItemState#ADDED}.
     *
     * <p>Read by the service to classify a line — a {@code CANCELLED} line holds no stock — so that
     * classification cannot fail on a catalog lookup and never falls back to a guess (the defect
     * {@code odd/tasks/sales-location-aware-stock.md} records as F-2). {@code V71} dropped the {@code status_id}
     * pointer that used to sit above it, so this column is the line's whole state and the label a screen shows
     * is projected from this code.</p>
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

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public String getTaxCode() {
        return taxCode;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public UUID getPromotionId() {
        return promotionId;
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

    public void setPromotionId(UUID promotionId) {
        this.promotionId = promotionId;
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

        public Builder promotionId(UUID promotionId) {
            item.promotionId = promotionId;
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
