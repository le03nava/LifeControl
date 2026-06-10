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

    @Column(name = "order_number", length = 30, nullable = false, unique = true)
    private String orderNumber;

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
