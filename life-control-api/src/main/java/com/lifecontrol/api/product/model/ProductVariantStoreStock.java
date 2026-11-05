package com.lifecontrol.api.product.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * What one company store records about one product variant: the <b>cost</b>.
 *
 * <p>Exactly one row per {@code (product_variant_id, company_store_id)} pair, enforced by
 * {@code UNIQUE(product_variant_id, company_store_id)}. It is no longer a position: ADR-0001 decision 1 made
 * {@code stock_movement} the only authority on quantity and {@code stock_position} its projection, so the
 * {@code (variant, store)} row keeps the two facts the ledger still needs from it — the cost every inbound
 * movement has to state ({@code ck_movement_inbound_cost}) — and an identity the per-store editor serializes on.
 * {@code V74} dropped the {@code stock} column this table carried since {@code V10}.</p>
 *
 * <p><b>There is no price here.</b> ADR-0006 decision 5 made the price a {@link VariantListing} —
 * {@code (variant, store)} with a price, a currency and no stock and no cost — because price and quantity have
 * different owners: a receipt may create stock in a store that never priced the article (decision 6), and that
 * state has to be representable. The {@code list_price} column this entity carried until {@code V77} held only
 * what {@code V58} had copied out of it, with no writer and no reader since T4b moved the reads; {@code V77}
 * dropped it. A reader that needs a price reads the listing.</p>
 *
 * <p>Shape: plain UUID foreign keys instead of JPA relations, and no {@code @Version} — concurrency is handled
 * by explicit pessimistic locks at the call sites.</p>
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

        public Builder costPrice(BigDecimal costPrice) {
            storeStock.costPrice = costPrice;
            return this;
        }

        public ProductVariantStoreStock build() {
            return storeStock;
        }
    }
}
