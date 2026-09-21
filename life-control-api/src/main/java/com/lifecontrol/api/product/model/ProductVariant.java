package com.lifecontrol.api.product.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Global sellable definition of a product variant: which product it belongs to, its name/size
 * and its barcode.
 *
 * <p>This row is GLOBAL, not per store. The stock and the prices that differ per store live in
 * {@link ProductVariantStoreStock}, one row per {@code (variant, store)}. The split exists because
 * the barcode of a given product in a given size is identical in every branch, so a store-scoped
 * row under a global unique barcode could never represent the same variant in two stores.</p>
 *
 * <p>Constraints owned by the database: {@code bar_code} is {@code NOT NULL} and globally unique
 * (D2), and {@code (product_id, variant_name)} is unique (D3). The entity deliberately carries no
 * {@code sku}: the variant sku was removed by decision D2, and the product sku is the only sku
 * that remains.</p>
 */
@Entity
@Table(name = "product_variants")
public class ProductVariant extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "bar_code", nullable = false, length = 100)
    private String barCode;

    @Column(name = "variant_name", nullable = false, length = 255)
    private String variantName;

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

    public String getBarCode() {
        return barCode;
    }

    public String getVariantName() {
        return variantName;
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

    public void setBarCode(String barCode) {
        this.barCode = barCode;
    }

    public void setVariantName(String variantName) {
        this.variantName = variantName;
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

        public Builder barCode(String barCode) {
            variant.barCode = barCode;
            return this;
        }

        public Builder variantName(String variantName) {
            variant.variantName = variantName;
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
