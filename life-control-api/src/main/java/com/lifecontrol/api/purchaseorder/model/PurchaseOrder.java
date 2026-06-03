package com.lifecontrol.api.purchaseorder.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.supplier.model.Supplier;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_number", length = 30, nullable = false, unique = true)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_store_id", nullable = false)
    private CompanyStore companyStore;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_method_id", nullable = false)
    private PaymentMethod paymentMethod;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(length = 500)
    private String comments;

    @Column(nullable = false)
    private Boolean enabled = true;

    @OneToMany(mappedBy = "purchaseOrder", fetch = FetchType.LAZY)
    private List<PurchaseOrderDetail> details = new ArrayList<>();

    // Default constructor for JPA
    public PurchaseOrder() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public CompanyStore getCompanyStore() {
        return companyStore;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public Status getStatus() {
        return status;
    }

    public String getComments() {
        return comments;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public List<PurchaseOrderDetail> getDetails() {
        return details;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public void setSupplier(Supplier supplier) {
        this.supplier = supplier;
    }

    public void setCompanyStore(CompanyStore companyStore) {
        this.companyStore = companyStore;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public void setComments(String comments) {
        this.comments = comments;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public void setDetails(List<PurchaseOrderDetail> details) {
        this.details = details;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final PurchaseOrder purchaseOrder = new PurchaseOrder();

        public Builder id(UUID id) {
            purchaseOrder.id = id;
            return this;
        }

        public Builder orderNumber(String orderNumber) {
            purchaseOrder.orderNumber = orderNumber;
            return this;
        }

        public Builder supplier(Supplier supplier) {
            purchaseOrder.supplier = supplier;
            return this;
        }

        public Builder companyStore(CompanyStore companyStore) {
            purchaseOrder.companyStore = companyStore;
            return this;
        }

        public Builder paymentMethod(PaymentMethod paymentMethod) {
            purchaseOrder.paymentMethod = paymentMethod;
            return this;
        }

        public Builder status(Status status) {
            purchaseOrder.status = status;
            return this;
        }

        public Builder comments(String comments) {
            purchaseOrder.comments = comments;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            purchaseOrder.enabled = enabled;
            return this;
        }

        public Builder details(List<PurchaseOrderDetail> details) {
            purchaseOrder.details = details;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            purchaseOrder.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            purchaseOrder.setUpdatedAt(updatedAt);
            return this;
        }

        public PurchaseOrder build() {
            return purchaseOrder;
        }
    }
}
