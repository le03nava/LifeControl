package com.lifecontrol.api.product.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Stock and pricing of one product variant in one company store.
 *
 * <p>Exactly one row per {@code (product_variant_id, company_store_id)} pair, enforced by
 * {@code UNIQUE(product_variant_id, company_store_id)}. This is the store-scoped half of the
 * variant model: {@link ProductVariant} holds the global sellable definition (product, variant
 * name, barcode) while this row holds what differs per store — the sellable {@code stock} and the
 * prices.</p>
 *
 * <p>The split exists because the previous single-table shape could not represent the same
 * physical variant in two stores: the barcode of a given product in a given size is identical in
 * every branch, yet it sat on a store-scoped row under a global unique index.</p>
 *
 * <p>Shape mirrors {@link com.lifecontrol.api.inventory.model.ProductVariantLocation}: plain UUID
 * foreign keys instead of JPA relations, and no {@code @Version} — concurrency is handled by
 * explicit pessimistic locks at the call sites, as documented there.</p>
 */
@Entity
@Table(name = "product_variant_store_stock")
public class ProductVariantStoreStock extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_variant_id", nullable = false)
    private UUID productVariantId;

    @Column(name = "company_store_id", nullable = false)
    private UUID companyStoreId;

    @Column(name = "stock", nullable = false, precision = 12, scale = 2)
    private BigDecimal stock = BigDecimal.ZERO;

    @Column(name = "list_price", precision = 12, scale = 2)
    private BigDecimal listPrice;

    @Column(name = "cost_price", precision = 12, scale = 2)
    private BigDecimal costPrice;

    // Default constructor for JPA
    public ProductVariantStoreStock() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public UUID getProductVariantId() {
        return productVariantId;
    }

    public UUID getCompanyStoreId() {
        return companyStoreId;
    }

    public BigDecimal getStock() {
        return stock;
    }

    public BigDecimal getListPrice() {
        return listPrice;
    }

    public BigDecimal getCostPrice() {
        return costPrice;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setProductVariantId(UUID productVariantId) {
        this.productVariantId = productVariantId;
    }

    public void setCompanyStoreId(UUID companyStoreId) {
        this.companyStoreId = companyStoreId;
    }

    public void setStock(BigDecimal stock) {
        this.stock = stock;
    }

    public void setListPrice(BigDecimal listPrice) {
        this.listPrice = listPrice;
    }

    public void setCostPrice(BigDecimal costPrice) {
        this.costPrice = costPrice;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ProductVariantStoreStock storeStock = new ProductVariantStoreStock();

        public Builder id(UUID id) {
            storeStock.id = id;
            return this;
        }

        public Builder productVariantId(UUID productVariantId) {
            storeStock.productVariantId = productVariantId;
            return this;
        }

        public Builder companyStoreId(UUID companyStoreId) {
            storeStock.companyStoreId = companyStoreId;
            return this;
        }

        public Builder stock(BigDecimal stock) {
            storeStock.stock = stock;
            return this;
        }

        public Builder listPrice(BigDecimal listPrice) {
            storeStock.listPrice = listPrice;
            return this;
        }

        public Builder costPrice(BigDecimal costPrice) {
            storeStock.costPrice = costPrice;
            return this;
        }

        public ProductVariantStoreStock build() {
            return storeStock;
        }
    }
}
