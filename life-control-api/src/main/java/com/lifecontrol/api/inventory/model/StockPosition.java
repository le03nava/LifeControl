package com.lifecontrol.api.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * The per-store quantity of one variant: {@code stock_position}, ADR-0001 decision 2's projection
 * ({@code V60}), and from T5b of {@code odd/tasks/inventory-ledger.md} the number every store-scoped read
 * answers with.
 *
 * <p><b>Read-only, and the mapping says so.</b> Decision 2 is "projections are derived, rebuildable, and
 * never authored": this table is written by {@code V61}'s {@code AFTER INSERT} trigger on
 * {@code stock_movement} and rebuilt in full by {@code rebuild_stock_projections}, so no Java path writes it
 * and this entity carries no setter, no builder and no save path. {@link Immutable} states that to Hibernate
 * as well — it is never dirty-checked and never flushed — which is stronger than a convention in a docblock:
 * a writer that reached it through JPA would have to change this class first.</p>
 *
 * <p><b>Why it exists at all.</b> The store-scoped reads are JPQL constructor projections
 * ({@code ProductVariantRepository}), and a {@code LEFT JOIN} onto a projection needs an entity to join. The
 * identifier is the table's own composite key {@code (company_store_id, product_variant_id)}, which is
 * exactly the grain a sale checks and the grain {@code stock_position} is keyed by; there is no surrogate id
 * to map.</p>
 *
 * <p><b>The two tenant columns are not decoration.</b> The table is armed on {@code tenant_table} and
 * {@code FORCE}d ({@code V60}), so a read of it runs as {@code lifecontrol_app} under ADR-0005's policy and
 * answers only inside a transaction that states the tenant. {@code company_id} is mapped because the row
 * carries it; the queries additionally filter on {@code companyStoreId}, which is a key column and therefore
 * free, and they inherit the tenant from the policy rather than restating it.</p>
 *
 * <p>{@code stock_balance} — the per-location projection — deliberately has no entity: no read in this tree
 * answers "how much is in this location". The one place that grain is read is the write path
 * ({@code InventoryService}), which reads and moves {@code product_variant_locations} until T5c stops that
 * write. Mapping a second projection with no reader would be an unused mapping, not a slice.</p>
 */
@Entity
@Table(name = "stock_position")
@IdClass(StockPosition.Key.class)
@Immutable
public class StockPosition {

    @Id
    @Column(name = "company_store_id", nullable = false)
    private UUID companyStoreId;

    @Id
    @Column(name = "product_variant_id", nullable = false)
    private UUID productVariantId;

    /** The tenant of the movement that produced this row, and the column ADR-0005's policy filters on. */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /**
     * The quantity on hand in this store — the number a sale checks and the operator sees. May be zero (a key
     * the ledger has moved and drained) and is absent when the ledger holds no movement for the key at all,
     * which is a different fact: see {@code ProductVariantResponse#stock()}.
     */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** For JPA only: nothing in this module instantiates a projection. */
    protected StockPosition() {}

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

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * The composite key {@code stock_position}'s primary key declares: one row per {@code (store, variant)}.
     *
     * <p>A class with a no-arg constructor and value semantics rather than a record, because {@code @IdClass}
     * is what JPA asks for and a record cannot give it one. It exists only so Hibernate can identify the row;
     * no caller builds one, since the repository's one operation is a scalar query by both columns.</p>
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
