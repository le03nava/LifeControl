package com.lifecontrol.api.salesorder.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sales_orders")
public class SalesOrder extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The printed number, kept for one more release (ADR-0003 migration step 9 drops it).
     *
     * <p>It is no longer globally unique: {@code V64} drops that constraint, because a number is
     * allocated per tenant and the second tenant's {@code SO-202610-00001} is legal. The identity the
     * database enforces is the parts below, and {@link #docNumber} is the same string written once.</p>
     */
    @Column(name = "order_number", length = 30, nullable = false)
    private String orderNumber;

    /** The tenant that owns the order and its number (ADR-0003 decision 5, invariant N9). */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** The closed document-type vocabulary of ADR-0003 decision 7: always {@code SALES_ORDER} here. */
    @Column(name = "doc_type", length = 24, nullable = false)
    private String docType;

    /** The period the number was allocated in, derived from {@link #orderDate} (decision 4). */
    @Column(name = "seq_period", length = 8, nullable = false)
    private String seqPeriod;

    /** The value the allocator handed out for {@code (companyId, docType, seqPeriod)}. */
    @Column(name = "seq_value", nullable = false)
    private Long seqValue;

    /** The rendered number, written once and never re-rendered on read (ADR-0003 decision 5). */
    @Column(name = "doc_number", length = 40, nullable = false)
    private String docNumber;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "company_store_id", nullable = false)
    private UUID companyStoreId;

    @Column(name = "shift_id")
    private UUID shiftId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "order_date", nullable = false)
    private LocalDateTime orderDate;

    /**
     * The order's typed state (ADR-0004 decision 1), and the only column that answers where the order is.
     *
     * <p><b>There used to be a {@code status_id} beside it and there is not one any more.</b> {@code V66}
     * added this column and a temporary bridge that derived it from the catalog while the pointer still
     * existed; {@code V71} (task T8) dropped the pointer, its indexes and the bridge, so what is left is the
     * enum's column and nothing to keep coherent with it. The catalog row a screen renders is a projection of
     * this code, read back by {@code StatusRepository#findByTypeNameAndCode} — a label, never an authority.</p>
     *
     * <p>Mapped as the enum and not as a string: {@code @Enumerated(STRING)} stores the constant's name,
     * which is exactly what the column's {@code CHECK} lists, and it makes an unmapped value a load failure
     * inside Hibernate instead of a guess in a service.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24)
    private SalesOrderState status;

    // ADR-0002 decision 1 and 3: `V80` widens this to `NUMERIC(19,4)` and makes it `NOT NULL`. The entity
    // states the same shape, because `ddl-auto=validate` compares the two and a widened column against a
    // two-decimal mapping fails the whole suite rather than one assertion.
    @Column(name = "total_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalAmount;

    // ADR-0002 decision 3: the document states its parts, and `V84` makes the four of them add up to
    // `totalAmount` exactly (`ck_order_total`). They are written by `SalesOrderService#recomputeBreakdown`
    // and by nothing else: a second writer is how the parts and the total stop agreeing without an error.
    @Column(name = "subtotal", precision = 19, scale = 4, nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_total", precision = 19, scale = 4, nullable = false)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "tax_total", precision = 19, scale = 4, nullable = false)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(name = "rounding_adjustment", precision = 19, scale = 4, nullable = false)
    private BigDecimal roundingAdjustment = BigDecimal.ZERO;

    @Column(name = "payment_method_id")
    private UUID paymentMethodId;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public SalesOrder() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public UUID getCompanyId() {
        return companyId;
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

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getCompanyStoreId() {
        return companyStoreId;
    }

    public UUID getShiftId() {
        return shiftId;
    }

    public String getUserId() {
        return userId;
    }

    public LocalDateTime getOrderDate() {
        return orderDate;
    }

    public SalesOrderState getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscountTotal() {
        return discountTotal;
    }

    public BigDecimal getTaxTotal() {
        return taxTotal;
    }

    public BigDecimal getRoundingAdjustment() {
        return roundingAdjustment;
    }

    public UUID getPaymentMethodId() {
        return paymentMethodId;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public void setCompanyId(UUID companyId) {
        this.companyId = companyId;
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

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public void setCompanyStoreId(UUID companyStoreId) {
        this.companyStoreId = companyStoreId;
    }

    public void setShiftId(UUID shiftId) {
        this.shiftId = shiftId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setOrderDate(LocalDateTime orderDate) {
        this.orderDate = orderDate;
    }

    public void setStatus(SalesOrderState status) {
        this.status = status;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    public void setDiscountTotal(BigDecimal discountTotal) {
        this.discountTotal = discountTotal;
    }

    public void setTaxTotal(BigDecimal taxTotal) {
        this.taxTotal = taxTotal;
    }

    public void setRoundingAdjustment(BigDecimal roundingAdjustment) {
        this.roundingAdjustment = roundingAdjustment;
    }

    public void setPaymentMethodId(UUID paymentMethodId) {
        this.paymentMethodId = paymentMethodId;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final SalesOrder order = new SalesOrder();

        public Builder id(UUID id) {
            order.id = id;
            return this;
        }

        public Builder orderNumber(String orderNumber) {
            order.orderNumber = orderNumber;
            return this;
        }

        public Builder companyId(UUID companyId) {
            order.companyId = companyId;
            return this;
        }

        public Builder docType(String docType) {
            order.docType = docType;
            return this;
        }

        public Builder seqPeriod(String seqPeriod) {
            order.seqPeriod = seqPeriod;
            return this;
        }

        public Builder seqValue(Long seqValue) {
            order.seqValue = seqValue;
            return this;
        }

        public Builder docNumber(String docNumber) {
            order.docNumber = docNumber;
            return this;
        }

        public Builder customerId(UUID customerId) {
            order.customerId = customerId;
            return this;
        }

        public Builder companyStoreId(UUID companyStoreId) {
            order.companyStoreId = companyStoreId;
            return this;
        }

        public Builder shiftId(UUID shiftId) {
            order.shiftId = shiftId;
            return this;
        }

        public Builder userId(String userId) {
            order.userId = userId;
            return this;
        }

        public Builder orderDate(LocalDateTime orderDate) {
            order.orderDate = orderDate;
            return this;
        }

        public Builder status(SalesOrderState status) {
            order.status = status;
            return this;
        }

        public Builder totalAmount(BigDecimal totalAmount) {
            order.totalAmount = totalAmount;
            return this;
        }

        public Builder paymentMethodId(UUID paymentMethodId) {
            order.paymentMethodId = paymentMethodId;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            order.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            order.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            order.setUpdatedAt(updatedAt);
            return this;
        }

        public SalesOrder build() {
            return order;
        }
    }
}
