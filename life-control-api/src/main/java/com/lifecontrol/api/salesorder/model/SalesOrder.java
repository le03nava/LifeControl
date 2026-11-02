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

    @Column(name = "status_id", nullable = false)
    private UUID statusId;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

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

    public UUID getStatusId() {
        return statusId;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
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

    public void setStatusId(UUID statusId) {
        this.statusId = statusId;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
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

        public Builder statusId(UUID statusId) {
            order.statusId = statusId;
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
