package com.lifecontrol.api.product.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "product_variants")
public class ProductVariant extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "company_store_id", nullable = false)
    private UUID companyStoreId;

    @Column(name = "bar_code", length = 100)
    private String barCode;

    @Column(name = "sku", length = 50)
    private String sku;

    @Column(name = "variant_name", length = 255)
    private String variantName;

    @Column(name = "list_price", precision = 12, scale = 2)
    private BigDecimal listPrice;

    @Column(name = "cost_price", precision = 12, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "stock", precision = 12, scale = 2)
    private BigDecimal stock = BigDecimal.ZERO;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public ProductVariant() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getCompanyStoreId() {
        return companyStoreId;
    }

    public String getBarCode() {
        return barCode;
    }

    public String getSku() {
        return sku;
    }

    public String getVariantName() {
        return variantName;
    }

    public BigDecimal getListPrice() {
        return listPrice;
    }

    public BigDecimal getCostPrice() {
        return costPrice;
    }

    public BigDecimal getStock() {
        return stock;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public void setCompanyStoreId(UUID companyStoreId) {
        this.companyStoreId = companyStoreId;
    }

    public void setBarCode(String barCode) {
        this.barCode = barCode;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public void setVariantName(String variantName) {
        this.variantName = variantName;
    }

    public void setListPrice(BigDecimal listPrice) {
        this.listPrice = listPrice;
    }

    public void setCostPrice(BigDecimal costPrice) {
        this.costPrice = costPrice;
    }

    public void setStock(BigDecimal stock) {
        this.stock = stock;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ProductVariant variant = new ProductVariant();

        public Builder id(UUID id) {
            variant.id = id;
            return this;
        }

        public Builder productId(UUID productId) {
            variant.productId = productId;
            return this;
        }

        public Builder companyStoreId(UUID companyStoreId) {
            variant.companyStoreId = companyStoreId;
            return this;
        }

        public Builder barCode(String barCode) {
            variant.barCode = barCode;
            return this;
        }

        public Builder sku(String sku) {
            variant.sku = sku;
            return this;
        }

        public Builder variantName(String variantName) {
            variant.variantName = variantName;
            return this;
        }

        public Builder listPrice(BigDecimal listPrice) {
            variant.listPrice = listPrice;
            return this;
        }

        public Builder costPrice(BigDecimal costPrice) {
            variant.costPrice = costPrice;
            return this;
        }

        public Builder stock(BigDecimal stock) {
            variant.stock = stock;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            variant.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            variant.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            variant.setUpdatedAt(updatedAt);
            return this;
        }

        public ProductVariant build() {
            return variant;
        }
    }
}
