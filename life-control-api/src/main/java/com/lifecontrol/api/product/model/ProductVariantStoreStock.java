package com.lifecontrol.api.product.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Stock and cost of one product variant in one company store: the <b>position</b>.
 *
 * <p>Exactly one row per {@code (product_variant_id, company_store_id)} pair, enforced by
 * {@code UNIQUE(product_variant_id, company_store_id)}. This is the store-scoped half of the
 * variant model: {@link ProductVariant} holds the global sellable definition (product, variant
 * name, barcode) while this row holds what differs per store — the units held there and their cost.</p>
 *
 * <p><b>{@code listPrice} is a leftover, and nothing writes it any more.</b> ADR-0006 decision 5 made the
 * price a {@link VariantListing} — {@code (variant, store)} with a price, a currency and no stock and no
 * cost — because price and quantity have different owners: a receipt may create stock in a store that
 * never priced the article (decision 6), and that state has to be representable. Since the price write
 * moved, {@code list_price} here holds only what {@code V58} copied out of it, and the column survives
 * because dropping it waits for ADR-0001's ledger: it is the same slice that turns {@code stock} into
 * movements ({@code docs/adr/0006-catalog-identity.md:150-152}), and this table drops with it. A reader
 * that needs a price reads the listing; a reader that reads this column is reading history.</p>
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
