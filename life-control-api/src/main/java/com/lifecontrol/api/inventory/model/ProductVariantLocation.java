package com.lifecontrol.api.inventory.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Stock balance of one product variant in one store location.
 *
 * <p>Exactly one row per {@code (product_variant_id, store_location_id)} pair, enforced by
 * {@code UNIQUE(product_variant_id, store_location_id)}. It is the per-location balance of the
 * inventory model (decision D1) and a separate row from the store-level aggregate in
 * {@code product_variant_store_stock}. Every writer — {@code InventoryService.applyReceipt},
 * {@code applySaleDeduction} and {@code applyStockAdjustment} — moves this balance and the aggregate
 * by the same amount, so the aggregate equals the sum of the store's location balances.</p>
 */
@Entity
@Table(name = "product_variant_locations")
public class ProductVariantLocation extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_variant_id", nullable = false)
    private UUID productVariantId;

    @Column(name = "store_location_id", nullable = false)
    private UUID storeLocationId;

    @Column(name = "stock", nullable = false, precision = 12, scale = 2)
    private BigDecimal stock = BigDecimal.ZERO;

    // Default constructor for JPA
    public ProductVariantLocation() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public UUID getProductVariantId() {
        return productVariantId;
    }

    public UUID getStoreLocationId() {
        return storeLocationId;
    }

    public BigDecimal getStock() {
        return stock;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setProductVariantId(UUID productVariantId) {
        this.productVariantId = productVariantId;
    }

    public void setStoreLocationId(UUID storeLocationId) {
        this.storeLocationId = storeLocationId;
    }

    public void setStock(BigDecimal stock) {
        this.stock = stock;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ProductVariantLocation location = new ProductVariantLocation();

        public Builder id(UUID id) {
            location.id = id;
            return this;
        }

        public Builder productVariantId(UUID productVariantId) {
            location.productVariantId = productVariantId;
            return this;
        }

        public Builder storeLocationId(UUID storeLocationId) {
            location.storeLocationId = storeLocationId;
            return this;
        }

        public Builder stock(BigDecimal stock) {
            location.stock = stock;
            return this;
        }

        public ProductVariantLocation build() {
            return location;
        }
    }
}
