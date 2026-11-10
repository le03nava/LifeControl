package com.lifecontrol.api.purchaseorder.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "purchase_order_details")
public class PurchaseOrderDetail extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_variant_id")
    private ProductVariant productVariant;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    /**
     * The line's base, {@code round(unit_price * quantity, 4)}, derived by {@code trg_purchase_order_details_money}
     * ({@code V92}).
     *
     * <p><b>Why it is not writable.</b> A derived column the application also sends is a column with two
     * writers, and the value a mapping carries is the value the row gets on any statement this trigger's column
     * list does not name — the stale-mapping trap {@code V81} records for sales. The same reasoning is why
     * there is no setter the service could call: the number is the schema's.</p>
     */
    @Column(precision = 19, scale = 4, nullable = false, insertable = false, updatable = false)
    private BigDecimal subtotal;

    /** {@code subtotal + tax_amount}. Same trigger. */
    @Column(precision = 19, scale = 4, nullable = false, insertable = false, updatable = false)
    private BigDecimal total;

    /**
     * The fiscal code this line was written with, copied from {@code products.tax_code} through the variant
     * ({@code V94}, S2).
     *
     * <p><b>Writable, unlike the three money columns.</b> This one is an <i>input</i> the service states at
     * creation — the product's code is the fact, and the copy onto the line is what makes a historical document
     * keep the tax it was written with. {@link #DEFAULT_TAX_CODE} is the column's own default and the value of
     * every line that predates {@code V94}: {@code NA} is a code with a meaning, not a missing value.</p>
     */
    @Column(name = "tax_code", length = 20, nullable = false)
    private String taxCode = DEFAULT_TAX_CODE;

    /**
     * The rate as it stood on the document's date when the line was created ({@code V94}, S3) — a snapshot, so
     * a later {@code tax_rate} row never restates a historical purchase.
     *
     * <p>It is the <b>input</b> of {@code trg_purchase_order_details_money}: the trigger listens to this column
     * rather than to the derived {@code tax_amount}, so stating the rate is what makes the schema compute the
     * tax. An edit of a line that stays on the same article keeps it; only a line moved to another variant is
     * re-resolved.</p>
     */
    @Column(name = "tax_rate", precision = 9, scale = 6, nullable = false)
    private BigDecimal taxRate = BigDecimal.ZERO;

    /**
     * {@code round(subtotal * tax_rate, 4)}, derived by the same trigger and function that derive
     * {@link #subtotal} and {@link #total} ({@code V94}, S6).
     *
     * <p><b>Why it is not writable.</b> The same reason {@link #subtotal} states one line up: a derived column
     * the application also sends has two writers, and the value a mapping carries is the value the row gets on
     * any statement this trigger's column list does not name. There is no setter, and the read-back is what
     * {@code PurchaseOrderService#refreshDerivedLineMoney} pays for on the paths that move the money.</p>
     */
    @Column(name = "tax_amount", precision = 19, scale = 4, nullable = false, insertable = false, updatable = false)
    private BigDecimal taxAmount;

    /** "No aplica": the fiscal code of a line no tax applies to, and the column's default ({@code V94}). */
    public static final String DEFAULT_TAX_CODE = "NA";

    @Column(name = "received_quantity", precision = 12, scale = 2, nullable = false)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;

    @Column(length = 500)
    private String comments;

    /**
     * The line's typed state (ADR-0004 decision 3), and the only column that answers where the line is: this
     * machine has its own tail ({@code IN_PROCESS}, {@code PARTIAL_RECEIVED}) and is not the sales-order item
     * set.
     *
     * <p><b>This is what {@code getStatus()} used to be, and the rename is the drop.</b> Until {@code V71} the
     * line carried a {@code Status} catalog pointer beside this enum; T8 deleted the pointer with its column,
     * so the enum is the whole state and the accessor keeps the name the rest of the tree reads.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24)
    private PurchaseOrderDetailState status;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public PurchaseOrderDetail() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public PurchaseOrder getPurchaseOrder() {
        return purchaseOrder;
    }

    public Product getProduct() {
        return product;
    }

    public ProductVariant getProductVariant() {
        return productVariant;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getTotal() {
        return total;
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

    public BigDecimal getReceivedQuantity() {
        return receivedQuantity;
    }

    public String getComments() {
        return comments;
    }

    /** The line's typed state (ADR-0004 decision 3); see {@link #status}. */
    public PurchaseOrderDetailState getStatus() {
        return status;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setPurchaseOrder(PurchaseOrder purchaseOrder) {
        this.purchaseOrder = purchaseOrder;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public void setProductVariant(ProductVariant productVariant) {
        this.productVariant = productVariant;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public void setTaxCode(String taxCode) {
        this.taxCode = taxCode;
    }

    public void setTaxRate(BigDecimal taxRate) {
        this.taxRate = taxRate;
    }

    /**
     * Kept for the fixtures that build a line and for nothing else: the column is {@code updatable = false}, so
     * an {@code UPDATE} that set it would be answered by {@code trg_purchase_order_details_money} with the
     * expression, and an {@code INSERT} never sends it at all. No production path calls this.
     */
    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public void setReceivedQuantity(BigDecimal receivedQuantity) {
        this.receivedQuantity = receivedQuantity;
    }

    public void setComments(String comments) {
        this.comments = comments;
    }

    public void setStatus(PurchaseOrderDetailState status) {
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
        private final PurchaseOrderDetail detail = new PurchaseOrderDetail();

        public Builder id(UUID id) {
            detail.id = id;
            return this;
        }

        public Builder purchaseOrder(PurchaseOrder purchaseOrder) {
            detail.purchaseOrder = purchaseOrder;
            return this;
        }

        public Builder product(Product product) {
            detail.product = product;
            return this;
        }

        public Builder productVariant(ProductVariant productVariant) {
            detail.productVariant = productVariant;
            return this;
        }

        public Builder quantity(BigDecimal quantity) {
            detail.quantity = quantity;
            return this;
        }

        public Builder unitPrice(BigDecimal unitPrice) {
            detail.unitPrice = unitPrice;
            return this;
        }

        public Builder total(BigDecimal total) {
            detail.total = total;
            return this;
        }

        public Builder taxCode(String taxCode) {
            detail.taxCode = taxCode;
            return this;
        }

        public Builder taxRate(BigDecimal taxRate) {
            detail.taxRate = taxRate;
            return this;
        }

        /** For fixtures that state a derived amount the trigger would otherwise answer. */
        public Builder taxAmount(BigDecimal taxAmount) {
            detail.taxAmount = taxAmount;
            return this;
        }

        public Builder receivedQuantity(BigDecimal receivedQuantity) {
            detail.receivedQuantity = receivedQuantity;
            return this;
        }

        public Builder comments(String comments) {
            detail.comments = comments;
            return this;
        }

        public Builder status(PurchaseOrderDetailState status) {
            detail.status = status;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            detail.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            detail.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            detail.setUpdatedAt(updatedAt);
            return this;
        }

        public PurchaseOrderDetail build() {
            return detail;
        }
    }
}
