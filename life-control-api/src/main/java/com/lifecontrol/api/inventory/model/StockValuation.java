package com.lifecontrol.api.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The cost of what one store holds of one variant: {@code stock_valuation}, ADR-0001 decision 4's projection
 * ({@code V60:221-230}), and the one number {@code V96} decided <b>not</b> to duplicate.
 *
 * <p><b>What it holds.</b> {@code quantity} and {@code total_value} are the value of the units on hand, and
 * {@code avg_unit_cost} is their quotient at four decimals ({@code V61}'s {@code replay_stock_valuation}):
 * Σ(inbound quantity × its {@code unit_cost}) − Σ(allocation quantity × its frozen {@code unit_cost}), divided by
 * the quantity. With the receipt booking the purchase price since {@code V96}, that quotient <em>is</em> the
 * weighted moving average of what was paid — which is why the average got no column of its own: a second one
 * written from Java could disagree with this row after any adjustment or consumption, and no reader could say
 * which answer was the cost.</p>
 *
 * <p><b>Read-only, and the mapping says so.</b> The row is written by {@code V61}'s
 * {@code project_stock_movement} trigger (through {@code refresh_stock_valuation}) and by the allocation writer,
 * never by Java: this entity has no setter, no builder and no save path, and {@link Immutable} states that to
 * Hibernate — it is never dirty-checked and never flushed. A writer that reached it through JPA would have to
 * change this class first, which is a stronger guarantee than a docblock.</p>
 *
 * <p><b>Why it exists at all.</b> The store-scoped variant reads answer {@code averageCost}, and the value has to
 * come from the projection the ledger maintains. A JPQL {@code LEFT JOIN} needs an entity to join, exactly as
 * {@link StockPosition} documents for the quantity, so the identifier is the table's own composite key
 * {@code (company_store_id, product_variant_id)} and there is no surrogate id to map.</p>
 *
 * <p><b>A missing row is a fact, not a zero.</b> The projection has a row only when the ledger holds a movement
 * for the key; a variant the store never moved has no valuation, so {@code averageCost} is {@code null} there —
 * the same meaning {@link StockPosition}'s absent row has for {@code stock}, and never a cost nobody recorded.
 * The table is armed on {@code tenant_table} and {@code FORCE}d ({@code V60}), so a read of it runs under
 * ADR-0005's policy and answers only inside a transaction that states the tenant.</p>
 */
@Entity
@Table(name = "stock_valuation")
@IdClass(StockValuation.Key.class)
@Immutable
public class StockValuation {

    @Id
    @Column(name = "company_store_id", nullable = false)
    private UUID companyStoreId;

    @Id
    @Column(name = "product_variant_id", nullable = false)
    private UUID productVariantId;

    /** The tenant of the movements that produced this row, and the column ADR-0005's policy filters on. */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** The quantity on hand the value belongs to: the position's number, restated by the same replay. */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    /**
     * The company's operating currency ({@code companies.currency}), the unit of both amounts.
     *
     * <p>{@link JdbcTypeCode} states the column's real type, exactly as {@code VariantListing.currency} and
     * {@code Company.currency} do: {@code V60} declares it {@code CHAR(3)}, and a {@code String} mapped by
     * default expects {@code varchar(3)}, which fails {@code ddl-auto: validate} at boot.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** The value of the units on hand — what the ledger's inbound rows left after the allocations consumed. */
    @Column(name = "total_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalValue;

    /**
     * {@code total_value / quantity} at four decimals, which is the weighted moving average a reader sees.
     * {@code null} when nothing is on hand: a quotient of an empty stock states no cost.
     */
    @Column(name = "avg_unit_cost", precision = 19, scale = 4)
    private BigDecimal avgUnitCost;

    /** For JPA only: nothing in this module instantiates a projection. */
    protected StockValuation() {}

    public UUID getCompanyStoreId() {
        return companyStoreId;
    }

    public UUID getProductVariantId() {
        return productVariantId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public BigDecimal getAvgUnitCost() {
        return avgUnitCost;
    }

    /**
     * The composite key {@code stock_valuation}'s primary key declares: one row per {@code (store, variant)}.
     *
     * <p>A class with a no-arg constructor and value semantics rather than a record, because {@code @IdClass} is
     * what JPA asks for and a record cannot give it one — the same shape {@link StockPosition.Key} has, and it
     * exists only so Hibernate can identify the row.</p>
     */
    public static class Key implements Serializable {

        private UUID companyStoreId;

        private UUID productVariantId;

        public Key() {}

        public Key(UUID companyStoreId, UUID productVariantId) {
            this.companyStoreId = companyStoreId;
            this.productVariantId = productVariantId;
        }

        public UUID getCompanyStoreId() {
            return companyStoreId;
        }

        public UUID getProductVariantId() {
            return productVariantId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(companyStoreId, key.companyStoreId)
                    && Objects.equals(productVariantId, key.productVariantId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(companyStoreId, productVariantId);
        }
    }
}
