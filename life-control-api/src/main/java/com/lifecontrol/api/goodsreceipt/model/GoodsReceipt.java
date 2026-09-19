package com.lifecontrol.api.goodsreceipt.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrder;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreLocation;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Immutable reception document raised against a purchase order.
 *
 * <p>One row per reception: it points at the purchase order it closes against, the store, the store
 * location where the goods were received, the receipt status and who received them when. Its lines
 * live in {@link GoodsReceiptItem}. The document is never edited after creation, so it carries no
 * {@code @Version} and the table has no {@code version} column.</p>
 *
 * <p>The receipt is an immutable fact: it is inserted once and never updated. {@link #receivedAt} is
 * assigned at persist time by the JPA pre-persist callback below (the {@code InventoryMovement}
 * precedent), and {@code created_at} / {@code updated_at} come from {@link Auditable}. The table's
 * {@code DEFAULT CURRENT_TIMESTAMP} never applies, because Hibernate always names the column in the
 * {@code INSERT} with an explicit {@code NULL}.</p>
 */
@Entity
@Table(name = "goods_receipts")
public class GoodsReceipt extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "receipt_number", length = 30, nullable = false, unique = true)
    private String receiptNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_store_id", nullable = false)
    private CompanyStore companyStore;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiving_location_id", nullable = false)
    private StoreLocation receivingLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(name = "received_by")
    private String receivedBy;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(length = 500)
    private String comments;

    @Column(nullable = false)
    private Boolean enabled = true;

    @OneToMany(mappedBy = "goodsReceipt", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<GoodsReceiptItem> items = new ArrayList<>();

    // Default constructor for JPA
    public GoodsReceipt() {}

    /**
     * Fills {@link #receivedAt} at persist time so the {@code received_at} NOT NULL column is never
     * written as an explicit {@code NULL}.
     *
     * <p>This method <strong>must not</strong> be named {@code onCreate}: {@link Auditable} already
     * declares {@code @PrePersist protected void onCreate()}, so a method with that same signature
     * here would <em>override</em> it and {@code Auditable}'s body would never run, leaving
     * {@code created_at} / {@code updated_at} unset and failing their NOT NULL columns. A distinct
     * name keeps both callbacks active. The set-if-null guard means an explicitly supplied value
     * still wins.</p>
     */
    @PrePersist
    protected void initializeReceivedAt() {
        if (receivedAt == null) {
            receivedAt = LocalDateTime.now();
        }
    }

    // Getters
    public UUID getId() {
        return id;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public PurchaseOrder getPurchaseOrder() {
        return purchaseOrder;
    }

    public CompanyStore getCompanyStore() {
        return companyStore;
    }

    public StoreLocation getReceivingLocation() {
        return receivingLocation;
    }

    public Status getStatus() {
        return status;
    }

    public String getReceivedBy() {
        return receivedBy;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public String getComments() {
        return comments;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public List<GoodsReceiptItem> getItems() {
        return items;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setReceiptNumber(String receiptNumber) {
        this.receiptNumber = receiptNumber;
    }

    public void setPurchaseOrder(PurchaseOrder purchaseOrder) {
        this.purchaseOrder = purchaseOrder;
    }

    public void setCompanyStore(CompanyStore companyStore) {
        this.companyStore = companyStore;
    }

    public void setReceivingLocation(StoreLocation receivingLocation) {
        this.receivingLocation = receivingLocation;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public void setReceivedBy(String receivedBy) {
        this.receivedBy = receivedBy;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public void setComments(String comments) {
        this.comments = comments;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public void setItems(List<GoodsReceiptItem> items) {
        this.items = items;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final GoodsReceipt receipt = new GoodsReceipt();

        public Builder id(UUID id) {
            receipt.id = id;
            return this;
        }

        public Builder receiptNumber(String receiptNumber) {
            receipt.receiptNumber = receiptNumber;
            return this;
        }

        public Builder purchaseOrder(PurchaseOrder purchaseOrder) {
            receipt.purchaseOrder = purchaseOrder;
            return this;
        }

        public Builder companyStore(CompanyStore companyStore) {
            receipt.companyStore = companyStore;
            return this;
        }

        public Builder receivingLocation(StoreLocation receivingLocation) {
            receipt.receivingLocation = receivingLocation;
            return this;
        }

        public Builder status(Status status) {
            receipt.status = status;
            return this;
        }

        public Builder receivedBy(String receivedBy) {
            receipt.receivedBy = receivedBy;
            return this;
        }

        public Builder receivedAt(LocalDateTime receivedAt) {
            receipt.receivedAt = receivedAt;
            return this;
        }

        public Builder comments(String comments) {
            receipt.comments = comments;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            receipt.enabled = enabled;
            return this;
        }

        public Builder items(List<GoodsReceiptItem> items) {
            receipt.items = items;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            receipt.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            receipt.setUpdatedAt(updatedAt);
            return this;
        }

        public GoodsReceipt build() {
            return receipt;
        }
    }
}
