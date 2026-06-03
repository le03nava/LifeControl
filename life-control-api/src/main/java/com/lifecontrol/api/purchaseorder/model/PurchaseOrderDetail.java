package com.lifecontrol.api.purchaseorder.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.status.model.Status;
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

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal total;

    @Column(name = "received_quantity", nullable = false)
    private Integer receivedQuantity = 0;

    @Column(length = 500)
    private String comments;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

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

    public Integer getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Integer getReceivedQuantity() {
        return receivedQuantity;
    }

    public String getComments() {
        return comments;
    }

    public Status getStatus() {
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

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public void setReceivedQuantity(Integer receivedQuantity) {
        this.receivedQuantity = receivedQuantity;
    }

    public void setComments(String comments) {
        this.comments = comments;
    }

    public void setStatus(Status status) {
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

        public Builder quantity(Integer quantity) {
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

        public Builder receivedQuantity(Integer receivedQuantity) {
            detail.receivedQuantity = receivedQuantity;
            return this;
        }

        public Builder comments(String comments) {
            detail.comments = comments;
            return this;
        }

        public Builder status(Status status) {
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
