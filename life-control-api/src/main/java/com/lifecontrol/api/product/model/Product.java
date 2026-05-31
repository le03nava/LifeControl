package com.lifecontrol.api.product.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 50, nullable = false, unique = true)
    private String sku;

    @Column(length = 255, nullable = false)
    private String name;

    @Column(length = 100)
    private String shortName;

    @Column(length = 20)
    private String satCode;

    @Column(length = 50)
    private String productType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> attributes;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Product() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getShortName() {
        return shortName;
    }

    public String getSatCode() {
        return satCode;
    }

    public String getProductType() {
        return productType;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public void setSatCode(String satCode) {
        this.satCode = satCode;
    }

    public void setProductType(String productType) {
        this.productType = productType;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Product product = new Product();

        public Builder id(UUID id) {
            product.id = id;
            return this;
        }

        public Builder sku(String sku) {
            product.sku = sku;
            return this;
        }

        public Builder name(String name) {
            product.name = name;
            return this;
        }

        public Builder shortName(String shortName) {
            product.shortName = shortName;
            return this;
        }

        public Builder satCode(String satCode) {
            product.satCode = satCode;
            return this;
        }

        public Builder productType(String productType) {
            product.productType = productType;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            product.attributes = attributes;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            product.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            product.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            product.setUpdatedAt(updatedAt);
            return this;
        }

        public Product build() {
            return product;
        }
    }
}
