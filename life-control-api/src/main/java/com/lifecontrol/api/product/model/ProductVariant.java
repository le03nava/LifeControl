package com.lifecontrol.api.product.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 *
 * <p>{@code measureUnitId} is the unit every quantity of this variant is expressed in (V29). It
 * lives here and not on {@link Product} because the same article is legitimately sold in pieces and
 * in kilograms, and only the variant — the sellable thing that owns the barcode and the per-store
 * price row — can carry one unit. It is nullable: a variant whose unit was never declared keeps
 * {@code null}, which is the behavior every variant had before the column existed. It is a plain
 * UUID, like the other foreign keys of the model, and the service refuses to change it once the
 * variant holds stock or ledger movements: the stored numbers carry no unit of their own, so
 * reinterpreting them silently is the one thing this column must never allow.</p>
 *
 * <p>{@code attributes} holds the VARIANT-level entries of the attribute profile that applies to the
 * product's category (V37): a shoe's colour and finish. The product owns the rest, and the level of
 * every attribute is declared by its definition, so one profile serves both screens. A colour belongs
 * here rather than on the product because two colours are two barcodes, two store rows and two prices.
 * The keys are definition codes and each value is validated by
 * {@code ProductAttributeCatalogService} before it is stored — never the raw JSON a client sent.</p>
 */
@Entity
@Table(name = "product_variants")
public class ProductVariant extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "measure_unit_id")
    private UUID measureUnitId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> attributes;

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

    public UUID getMeasureUnitId() {
        return measureUnitId;
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

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public void setMeasureUnitId(UUID measureUnitId) {
        this.measureUnitId = measureUnitId;
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

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
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

        public Builder measureUnitId(UUID measureUnitId) {
            variant.measureUnitId = measureUnitId;
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

        public Builder attributes(Map<String, Object> attributes) {
            variant.attributes = attributes;
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
