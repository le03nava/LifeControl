package com.lifecontrol.api.purchaseorder.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.paymentmethod.model.PaymentMethod;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.supplier.model.Supplier;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@NamedEntityGraph(
        name = "PurchaseOrder.withHierarchy",
        attributeNodes = {
            @NamedAttributeNode("supplier"),
            @NamedAttributeNode(value = "companyStore", subgraph = "store-graph"),
            @NamedAttributeNode("paymentMethod"),
            @NamedAttributeNode("details")
        },
        subgraphs = {
            @NamedSubgraph(
                    name = "store-graph",
                    attributeNodes = {@NamedAttributeNode(value = "companyZone", subgraph = "zone-graph")}),
            @NamedSubgraph(
                    name = "zone-graph",
                    attributeNodes = {@NamedAttributeNode(value = "companyRegion", subgraph = "region-graph")}),
            @NamedSubgraph(
                    name = "region-graph",
                    attributeNodes = {@NamedAttributeNode(value = "companyCountry", subgraph = "country-graph")}),
            @NamedSubgraph(
                    name = "country-graph",
                    attributeNodes = {@NamedAttributeNode("company")})
        })
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The printed number, kept for one more release (ADR-0003 migration step 9 drops it).
     *
     * <p>It is no longer globally unique: {@code V64} drops that constraint, because a number is
     * allocated per tenant and the second tenant's {@code PO-202610-00001} is legal. The identity the
     * database enforces is the parts below, and {@link #docNumber} is the same string written once.</p>
     */
    @Column(name = "order_number", length = 30, nullable = false)
    private String orderNumber;

    /** The closed document-type vocabulary of ADR-0003 decision 7: always {@code PURCHASE_ORDER} here. */
    @Column(name = "doc_type", length = 24, nullable = false)
    private String docType;

    /**
     * The period the number was allocated in (ADR-0003 decision 4).
     *
     * <p>The period comes from the document's own date and this table carries no {@code order_date}
     * column, so {@code PurchaseOrderService} freezes one date and derives the period from it; see the
     * deviation recorded in that method.</p>
     */
    @Column(name = "seq_period", length = 8, nullable = false)
    private String seqPeriod;

    /** The value the allocator handed out for {@code (companyId, docType, seqPeriod)}. */
    @Column(name = "seq_value", nullable = false)
    private Long seqValue;

    /** The rendered number, written once and never re-rendered on read (ADR-0003 decision 5). */
    @Column(name = "doc_number", length = 40, nullable = false)
    private String docNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_store_id", nullable = false)
    private CompanyStore companyStore;

    /**
     * The company that owns this order — the company of {@link #companyStore}, stored so the key can
     * carry it.
     *
     * <p>Denormalized on purpose, in {@link com.lifecontrol.api.product.model.Product}'s shape: the
     * store already reaches its company through zone &rarr; region &rarr; country (V1:127-136), so this
     * is a copy of a derivable fact. It is stored because a composite foreign key can only reference a
     * column that exists on the row, and {@code (company_id, supplier_id)} is what makes "a supplier of
     * another company" unreachable.</p>
     *
     * <p>Required, and enforced by the database since {@code V43}: {@code purchase_orders.company_id} is
     * {@code NOT NULL} and {@code fk_purchase_orders_supplier_same_company} refuses a supplier of a
     * different company. What the database still cannot state is the other half — that this company is
     * the store's company — because {@code company_stores} carries no {@code company_id} to key on
     * ({@code V43}'s header records that limit). {@code PurchaseOrderService} sets it from the store on
     * every write, through {@code CompanyStoreOwnerResolver}, in the same breath as the supplier check.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_method_id", nullable = false)
    private PaymentMethod paymentMethod;

    /**
     * The order's typed state (ADR-0004 decision 1), and the only column that answers where the order is.
     *
     * <p><b>This is what {@code getStatus()} used to be, and the rename is the drop.</b> Until {@code V71}
     * there were two fields: this enum in {@code state} and a {@code Status} catalog pointer in
     * {@code status}, kept coherent by {@code V66}'s bridge and by one service helper. T8 deleted the pointer,
     * its indexes and the bridge, so the enum is the whole state and the accessor keeps the name the rest of
     * the tree reads: {@link #getStatus()}.</p>
     *
     * <p>Mapped as the enum and not as a string: {@code @Enumerated(STRING)} stores the constant's name, which
     * is exactly what the column's {@code CHECK} lists, and an unmapped value becomes a load failure inside
     * Hibernate instead of a guess in a service.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24)
    private PurchaseOrderState status;

    @Column(length = 500)
    private String comments;

    @Column(nullable = false)
    private Boolean enabled = true;

    // ─── the document's money, derived by the schema (`V93`) ───────────────
    //
    // Four columns no code in this module writes, and none of them has a setter for that reason: `currency`
    // comes from the company on insert and freezes there (S4), and the three amounts follow the enabled lines
    // through `trg_purchase_order_details_document_money`. They are mapped non-writable so Hibernate cannot
    // send a number the schema would then disagree with, which means a freshly built order carries `null` here
    // until it is read back — `PurchaseOrderService#refreshDerivedOrderMoney` is what pays for that read, and
    // only on the paths that just changed a line.

    /**
     * The currency the order was written in, frozen at creation (S4).
     *
     * <p>{@link JdbcTypeCode} states the column's real type: it is {@code CHAR(3)}, and a {@code String} mapped
     * by default expects {@code varchar(3)}, which fails {@code ddl-auto: validate} at boot — the same mapping
     * {@code Company.currency} carries for the same reason.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3, nullable = false, insertable = false, updatable = false)
    private String currency;

    @Column(precision = 19, scale = 4, nullable = false, insertable = false, updatable = false)
    private BigDecimal subtotal;

    @Column(name = "tax_total", precision = 19, scale = 4, nullable = false, insertable = false, updatable = false)
    private BigDecimal taxTotal;

    @Column(name = "total_amount", precision = 19, scale = 4, nullable = false, insertable = false, updatable = false)
    private BigDecimal totalAmount;

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getTaxTotal() {
        return taxTotal;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    @OneToMany(mappedBy = "purchaseOrder", fetch = FetchType.LAZY)
    private List<PurchaseOrderDetail> details = new ArrayList<>();

    // Default constructor for JPA
    public PurchaseOrder() {}

    /**
     * Keeps the instant the order was numbered across persist, and still lets the inherited callback stamp an
     * entity that froze none.
     *
     * <p><b>Why an override and not a second callback.</b> {@link Auditable} stamps both audit columns from its
     * own {@code @PrePersist}, and this override <i>replaces</i> it: exactly one callback body runs, and
     * {@code super.onCreate()} is called explicitly rather than left to the hierarchy's callback ordering. A
     * separate {@code @PrePersist} beside the inherited one would hand the outcome to that ordering, and an
     * override that skipped {@code super.onCreate()} would leave both columns null and fail their
     * {@code NOT NULL} constraints (the failure mode {@code GoodsReceiptIntegrationTest} records for a
     * callback that shadows the inherited one).</p>
     *
     * <p><b>Why the frozen instant wins.</b> {@code PurchaseOrderService} reads the clock once and derives both
     * the number's period and this instant from that one value, because {@code purchase_orders} carries no
     * {@code order_date} column ({@code V1__baseline_schema.sql:317-328}) and {@code created_at} is therefore
     * the document's only recorded date (ADR-0003 decision 4). A second reading at flush would put a number for
     * one month on a row dated the next, which is the defect issue #772 fixes.</p>
     */
    @Override
    @PrePersist
    protected void onCreate() {
        var frozen = getCreatedAt();
        super.onCreate();
        if (frozen != null) {
            setCreatedAt(frozen);
            setUpdatedAt(frozen);
        }
    }

    // Getters
    public UUID getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getDocType() {
        return docType;
    }

    public String getSeqPeriod() {
        return seqPeriod;
    }

    public Long getSeqValue() {
        return seqValue;
    }

    public String getDocNumber() {
        return docNumber;
    }

    public Supplier getSupplier() {
        return supplier;
    }

    public CompanyStore getCompanyStore() {
        return companyStore;
    }

    public Company getCompany() {
        return company;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    /** The order's typed state (ADR-0004 decision 1); see {@link #status} for what the drop changed. */
    public PurchaseOrderState getStatus() {
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

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public void setSeqPeriod(String seqPeriod) {
        this.seqPeriod = seqPeriod;
    }

    public void setSeqValue(Long seqValue) {
        this.seqValue = seqValue;
    }

    public void setDocNumber(String docNumber) {
        this.docNumber = docNumber;
    }

    public void setSupplier(Supplier supplier) {
        this.supplier = supplier;
    }

    public void setCompanyStore(CompanyStore companyStore) {
        this.companyStore = companyStore;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public void setStatus(PurchaseOrderState status) {
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

        public Builder docType(String docType) {
            purchaseOrder.docType = docType;
            return this;
        }

        public Builder seqPeriod(String seqPeriod) {
            purchaseOrder.seqPeriod = seqPeriod;
            return this;
        }

        public Builder seqValue(Long seqValue) {
            purchaseOrder.seqValue = seqValue;
            return this;
        }

        public Builder docNumber(String docNumber) {
            purchaseOrder.docNumber = docNumber;
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

        public Builder company(Company company) {
            purchaseOrder.company = company;
            return this;
        }

        public Builder paymentMethod(PaymentMethod paymentMethod) {
            purchaseOrder.paymentMethod = paymentMethod;
            return this;
        }

        public Builder status(PurchaseOrderState status) {
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
