package com.lifecontrol.api.product.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A price in one store: the listing of ADR-0006 decision 5 ({@code variant_listing}, {@code V58}).
 *
 * <p><b>Why this is a separate row from {@link ProductVariantStoreStock}.</b> Decision 5
 * (<code>docs/adr/0006-catalog-identity.md:57-59</code>) splits two facts that shared one lifecycle until
 * {@code V58}: "this store sells the variant, at this price" is a listing, and "there are units here" is the
 * position. Before the split the price screen was also a stock editor, and a receipt into a store that had
 * never priced an article hid it instead of reporting "stock without price" — the state decision 6
 * (<code>:60-64</code>) names and this slice stops hiding.</p>
 *
 * <p><b>What it deliberately does not carry.</b> No {@code stock} and no {@code cost_price}: quantity is
 * ADR-0001's ledger and cost is the inbound movement's (invariant C7,
 * <code>docs/adr/0006-catalog-identity.md:138</code>). No {@code tax_rate_id} either, and that is a
 * reported departure rather than an omission: {@code tax_rate} does not exist in the tree yet — it is
 * ADR-0002's T4, gated on its decision D-d — so {@code V58:33-39} adds the column later, by
 * {@code ALTER}, in the slice that creates the table it references.</p>
 *
 * <p><b>No {@code Auditable}.</b> {@code variant_listing} has no {@code created_at}/{@code updated_at}
 * columns, so extending {@code Auditable} would map two columns a database booted at head does not have —
 * and the PostgreSQL suites validate the mapping against the migrations, so that mismatch is a failure and
 * not a surprise in production.</p>
 *
 * <p>Shape mirrors {@link ProductVariantStoreStock}: plain UUID foreign keys instead of JPA relations, and
 * no {@code @Version} — the per-{@code (variant, store)} row is the concurrency point, and the price path
 * reaches it with a single {@code INSERT … ON CONFLICT} statement instead of a read-modify-write.</p>
 */
@Entity
@Table(name = "variant_listing")
public class VariantListing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The tenant of the variant <b>and</b> of the store, which {@code fk_listing_variant} and
     * {@code fk_listing_store} make one row's obligation rather than a convention (invariant C6):
     * {@code company_id} is the leading column of both composite keys.
     */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "product_variant_id", nullable = false)
    private UUID productVariantId;

    @Column(name = "company_store_id", nullable = false)
    private UUID companyStoreId;

    /** {@code NUMERIC(19,4)}: ADR-0002 decision 1's scale, wider than the store row's 12,2. */
    @Column(name = "list_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal listPrice;

    /**
     * The operating currency of the variant's company ({@code companies.currency}, {@code V57}), copied at
     * write time. No default and no derivation from the store: a listing states the money it is priced in.
     *
     * <p>{@link JdbcTypeCode} states the column's real type, exactly as {@code Company.currency} does: it is
     * {@code CHAR(3)}, and a {@code String} mapped by default expects {@code varchar(3)}, which fails
     * {@code ddl-auto: validate} at boot — measured on the first run of this slice, where the whole
     * PostgreSQL suite refused to start the context over this column alone.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /**
     * Availability flag, {@code true} by default in the schema. Nothing in this slice writes {@code false}:
     * the reads decide "listed" by the presence of the row, and a flag nobody can set is not read as if it
     * could be — that is why {@code enabled} is carried here and not consulted by the two store-scoped
     * queries yet.
     */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    // Default constructor for JPA
    public VariantListing() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getProductVariantId() {
        return productVariantId;
    }

    public UUID getCompanyStoreId() {
        return companyStoreId;
    }

    public BigDecimal getListPrice() {
        return listPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setCompanyId(UUID companyId) {
        this.companyId = companyId;
    }

    public void setProductVariantId(UUID productVariantId) {
        this.productVariantId = productVariantId;
    }

    public void setCompanyStoreId(UUID companyStoreId) {
        this.companyStoreId = companyStoreId;
    }

    public void setListPrice(BigDecimal listPrice) {
        this.listPrice = listPrice;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final VariantListing listing = new VariantListing();

        public Builder id(UUID id) {
            listing.id = id;
            return this;
        }

        public Builder companyId(UUID companyId) {
            listing.companyId = companyId;
            return this;
        }

        public Builder productVariantId(UUID productVariantId) {
            listing.productVariantId = productVariantId;
            return this;
        }

        public Builder companyStoreId(UUID companyStoreId) {
            listing.companyStoreId = companyStoreId;
            return this;
        }

        public Builder listPrice(BigDecimal listPrice) {
            listing.listPrice = listPrice;
            return this;
        }

        public Builder currency(String currency) {
            listing.currency = currency;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            listing.enabled = enabled;
            return this;
        }

        public VariantListing build() {
            return listing;
        }
    }
}
