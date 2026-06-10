package com.lifecontrol.api.promotion.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "promotions")
public class Promotion extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "promotion_name", nullable = false)
    private String promotionName;

    @Column(name = "discount_type", nullable = false, length = 50)
    private String discountType;

    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "coupon_code", length = 50)
    private String couponCode;

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(name = "end_date")
    private LocalDateTime endDate;

    @Column(name = "sales_channel", length = 50)
    private String salesChannel;

    @Column(name = "minimum_purchase_amount", precision = 12, scale = 2)
    private BigDecimal minimumPurchaseAmount;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Promotion() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getPromotionName() {
        return promotionName;
    }

    public String getDiscountType() {
        return discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public LocalDateTime getStartDate() {
        return startDate;
    }

    public LocalDateTime getEndDate() {
        return endDate;
    }

    public String getSalesChannel() {
        return salesChannel;
    }

    public BigDecimal getMinimumPurchaseAmount() {
        return minimumPurchaseAmount;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setPromotionName(String promotionName) {
        this.promotionName = promotionName;
    }

    public void setDiscountType(String discountType) {
        this.discountType = discountType;
    }

    public void setDiscountValue(BigDecimal discountValue) {
        this.discountValue = discountValue;
    }

    public void setCouponCode(String couponCode) {
        this.couponCode = couponCode;
    }

    public void setStartDate(LocalDateTime startDate) {
        this.startDate = startDate;
    }

    public void setEndDate(LocalDateTime endDate) {
        this.endDate = endDate;
    }

    public void setSalesChannel(String salesChannel) {
        this.salesChannel = salesChannel;
    }

    public void setMinimumPurchaseAmount(BigDecimal minimumPurchaseAmount) {
        this.minimumPurchaseAmount = minimumPurchaseAmount;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Promotion promotion = new Promotion();

        public Builder id(UUID id) {
            promotion.id = id;
            return this;
        }

        public Builder promotionName(String promotionName) {
            promotion.promotionName = promotionName;
            return this;
        }

        public Builder discountType(String discountType) {
            promotion.discountType = discountType;
            return this;
        }

        public Builder discountValue(BigDecimal discountValue) {
            promotion.discountValue = discountValue;
            return this;
        }

        public Builder couponCode(String couponCode) {
            promotion.couponCode = couponCode;
            return this;
        }

        public Builder startDate(LocalDateTime startDate) {
            promotion.startDate = startDate;
            return this;
        }

        public Builder endDate(LocalDateTime endDate) {
            promotion.endDate = endDate;
            return this;
        }

        public Builder salesChannel(String salesChannel) {
            promotion.salesChannel = salesChannel;
            return this;
        }

        public Builder minimumPurchaseAmount(BigDecimal minimumPurchaseAmount) {
            promotion.minimumPurchaseAmount = minimumPurchaseAmount;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            promotion.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            promotion.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            promotion.setUpdatedAt(updatedAt);
            return this;
        }

        public Promotion build() {
            return promotion;
        }
    }
}
