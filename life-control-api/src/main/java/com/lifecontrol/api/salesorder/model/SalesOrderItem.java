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
     * The line's total as the schema derives it:
     * {@code round(list_price * quantity, 4) - discount_applied + tax_amount}, by the one function
     * {@code sales_order_item_tax_and_total()} through its trigger {@code trg_sales_order_items_tax_and_total},
     * never by this mapping.
     *
     * <p><b>Why the function, and not the trigger {@code V85} installed.</b> Until {@code V90} this column had a
     * trigger of its own, {@code trg_sales_order_items_final_price}, whose function read {@code NEW.tax_amount};
     * {@code V90} dropped that pair and derives both columns from {@code tax_rate} in one function, because
     * same-event triggers fire in name order and a separate {@code tax_amount} trigger would sort after the
     * total's and be read stale (S5 of {@code odd/tasks/product-tax-code.md}). The name is stated here so a
     * reader looking for the writer finds a trigger that exists.</p>
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

    /**
     * The catalog code the rate was resolved from (ADR-0002 decision 6), copied from the sold variant's product
     * at write time and never restated.
     *
     * <p><b>Why writable, and why only a writer that creates a line states it.</b> {@code V81} created this
     * column with the default {@code NA} and, until T4, nothing in Java ever wrote it — the mapping said
     * {@code insertable = false, updatable = false} and every line stayed at the default whether its product
     * declared {@code IVA} or not. The copy is the writer (S1 of {@code odd/tasks/product-tax-code.md}), so the
     * column is mapped like every other field a request may set. What is <b>not</b> writable is a client's: the
     * code comes from the product, and {@code SalesOrderItemRequest} deliberately carries no field for it (S2) —
     * a document stating its own code would be the server guessing a fiscal fact.</p>
     *
     * <p>An edit of an existing line does not re-resolve it. The stored pair is the snapshot of the sale; a
     * re-resolution on a later edit could only pick up a rate stated afterwards, which is exactly what decision 6
     * forbids (the service says the same where it implements it).</p>
     */
    @Column(name = "tax_code", length = 20, nullable = false)
    private String taxCode = "NA";

    /**
     * The rate as it stood at write time: a snapshot, so a later {@code tax_rate} row never restates history.
     *
     * <p>Writable for the same reason as {@link #taxCode}, and it is the <b>input</b> of the derivation
     * {@code V90} installed: the trigger listens to this column rather than to the derived {@code tax_amount}, so
     * stating the rate is what makes the schema compute the tax. {@code V81} gives it the default {@code 0}, and
     * a line whose code is zero by definition (D-a) carries exactly that.</p>
     */
    @Column(name = "tax_rate", precision = 9, scale = 6, nullable = false)
    private BigDecimal taxRate = BigDecimal.ZERO;

    /**
     * `round((subtotal - discount_applied) * tax_rate, 4)`, derived by the same function as {@link #finalPrice}
     * and held by `ck_item_tax`.
     *
     * <p>Still read-only, and for the reason {@code V90} exists: the trigger writes it from the rate the row
     * carries, so a value stated by Java would be overwritten on the way in — and, on a statement whose column
     * list does not name the rate, a stale mapped value would be the one that lands and {@code ck_item_tax} would
     * refuse the write. {@code V90}'s own suite pins that a hand-written amount that disagrees is refused.</p>
     */
    @Column(name = "tax_amount", precision = 19, scale = 4, nullable = false, insertable = false, updatable = false)
    private BigDecimal taxAmount;

    /**
     * The product's name as the catalog gave it when the line was written, so a ticket reprinted in a year reads
     * the same whatever the catalog says then (ADR-0002 decision 4).
     *
     * <p><b>Why read-only.</b> {@code V81} is the only writer this column has ever had: the migration backfilled
     * every line from the variant's product, and no application path states one — not the create, not the item
     * endpoints, not the order's {@code PUT}. The mapping says what is true rather than promising a writer that
     * does not exist. Mapping it and then editing it would also be the one thing the snapshot must not do: the
     * whole point is that it is the value read at sale time, not the one read now.</p>
     *
     * <p>It is answered by {@link com.lifecontrol.api.salesorder.dto.SalesOrderItemResponse} as it is stored, and
     * is therefore {@code null} on every line whose sale happened after the migration: that is a line with no
     * snapshot, not a line with a wrong one, and filling it in the reader would invent the moment decision 4
     * pins.</p>
     */
    @Column(name = "product_name_snapshot", length = 255, insertable = false, updatable = false)
    private String productNameSnapshot;

    /** The variant's name, on the same terms and with the same single writer as {@link #productNameSnapshot}. */
    @Column(name = "variant_name_snapshot", length = 255, insertable = false, updatable = false)
    private String variantNameSnapshot;

    // `V81` also added `attributes_snapshot` (JSONB), and that column stays unmapped on purpose: nothing in the
    // tree reads it, and the two names above are what ADR-0002 step 5 says a reprint needs. A mapping with no
    // reader and no writer is a second copy of a `jsonb` payload nothing owns; it can be mapped in the slice that
    // states what reads it, and the format it arrives in is that slice's decision, not this one's.

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

    /**
     * Sets the code this line snapshots. Used only by the one path that may legitimately restate it: a line
     * moved to another variant, which is no longer the article whose tax was copied. A plain edit cannot
     * restate the snapshot — ADR-0002 decision 6 — so this setter is not part of the ordinary update path.
     */
    public void setTaxCode(String taxCode) {
        this.taxCode = taxCode;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    /** Sets the rate that pairs with {@link #setTaxCode(String)}; the derivation reads both. */
    public void setTaxRate(BigDecimal taxRate) {
        this.taxRate = taxRate;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public String getProductNameSnapshot() {
        return productNameSnapshot;
    }

    public String getVariantNameSnapshot() {
        return variantNameSnapshot;
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

        /**
         * The code the sale copies from the product (S1, S2 of {@code odd/tasks/product-tax-code.md}). Stated by
         * the service at the moment a line row is created — never by a request, which has no such field.
         */
        public Builder taxCode(String taxCode) {
            item.taxCode = taxCode;
            return this;
        }

        /** The rate the document's date resolved, copied onto the line as the snapshot decision 6 describes. */
        public Builder taxRate(BigDecimal taxRate) {
            item.taxRate = taxRate;
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
