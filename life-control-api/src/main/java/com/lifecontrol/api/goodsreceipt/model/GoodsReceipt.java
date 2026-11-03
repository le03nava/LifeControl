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

    /**
     * The printed number, kept for one more release (ADR-0003 migration step 9 drops it).
     *
     * <p>It is no longer globally unique: {@code V64} drops that constraint, and the number is derived
     * from the parent order's frozen {@code doc_number}, which two tenants may print identically. The
     * rendered value is also stored in {@link #docNumber}, which is the column readers will use.</p>
     */
    @Column(name = "receipt_number", length = 30, nullable = false)
    private String receiptNumber;

    /** The tenant that owns the receipt, derived from its purchase order (ADR-0003 invariant N9). */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** The closed document-type vocabulary of ADR-0003 decision 7: always {@code GOODS_RECEIPT} here. */
    @Column(name = "doc_type", length = 24, nullable = false)
    private String docType;

    /** The rendered {@code GR-{parent.doc_number}-{NN}}, written once (ADR-0003 decision 6). */
    @Column(name = "doc_number", length = 40, nullable = false)
    private String docNumber;

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

    /**
     * The receipt's typed state (ADR-0004 decision 1), and the column {@code V70} now keeps history for.
     *
     * <p><b>Two columns, one fact.</b> {@code status} (this field, the {@code VARCHAR(24)} of {@code V66}) is the
     * state; {@code status_id} (the {@link Status} above) is the catalog pointer the screens read until T8 drops
     * it. {@code V66}'s bridge refuses the two ever disagreeing, so a writer that sets one and not the other
     * cannot commit a row that says two things.</p>
     *
     * <p>Mapped as the enum and not as a string, exactly as {@code SalesOrder.status} is: the stored value is the
     * constant's name, which is what the column's {@code CHECK} lists, so an unmapped value is a load failure
     * rather than a guess. It is deliberately not marked {@code nullable = false}: the column is, and leaving the
     * annotation silent lets a fixture that omits it reach {@code V66}'s bridge, which derives it from the
     * catalog's code — the path every receipt took before this slice.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24)
    private GoodsReceiptState state;

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

    public UUID getCompanyId() {
        return companyId;
    }

    public String getDocType() {
        return docType;
    }

    public String getDocNumber() {
        return docNumber;
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

    /** The receipt's typed state (ADR-0004 decision 1); see {@link #state} for why the pointer keeps its name. */
    public GoodsReceiptState getState() {
        return state;
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

    public void setCompanyId(UUID companyId) {
        this.companyId = companyId;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public void setDocNumber(String docNumber) {
        this.docNumber = docNumber;
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

    public void setState(GoodsReceiptState state) {
        this.state = state;
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

        public Builder companyId(UUID companyId) {
            receipt.companyId = companyId;
            return this;
        }

        public Builder docType(String docType) {
            receipt.docType = docType;
            return this;
        }

        public Builder docNumber(String docNumber) {
            receipt.docNumber = docNumber;
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

        public Builder state(GoodsReceiptState state) {
            receipt.state = state;
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
