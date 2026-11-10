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

    /** {@code subtotal + tax_amount} — today {@code subtotal}, because this slice states no tax. Same trigger. */
    @Column(precision = 19, scale = 4, nullable = false, insertable = false, updatable = false)
    private BigDecimal total;

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
