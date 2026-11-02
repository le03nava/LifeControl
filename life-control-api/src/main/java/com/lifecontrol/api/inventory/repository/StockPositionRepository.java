package com.lifecontrol.api.inventory.repository;

import com.lifecontrol.api.inventory.model.StockPosition;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * The one read of {@code stock_position} from Java: how much of one variant one store holds, answered by the
 * ledger's projection (ADR-0001 decision 2, T5b of {@code odd/tasks/inventory-ledger.md}).
 *
 * <p><b>Why this is a {@link org.springframework.data.repository.Repository} and not a
 * {@code JpaRepository}.</b> Decision 2 is "never authored":
 * the table is written by {@code V61}'s trigger and rebuilt by {@code rebuild_stock_projections}, so a write
 * method on this interface would be a door the design forbids. Extending the marker interface means the only
 * operation that exists is the query below — a caller cannot save, delete or flush a projection even by
 * accident, and {@code StockLedgerEngineIntegrationTest} keeps proving the trigger is the only writer.</p>
 *
 * <p><b>Empty is a state and not an error.</b> The ledger holds no row for a {@code (store, variant)} nothing
 * has ever moved, which is why the return is an {@code Optional} rather than a zero: an absent position and a
 * position of zero are different facts (a key the ledger drained to zero has a row saying so), and the
 * store-scoped reads report the difference instead of collapsing it — see
 * {@code ProductVariantResponse#stock()}.</p>
 *
 * <p><b>Tenant scope.</b> {@code stock_position} is armed on {@code tenant_table} and {@code FORCE}d
 * ({@code V60}), and this interface states no scope of its own: it runs inside the caller's transaction, whose
 * boundary installed ADR-0005's {@code app.company_id} ({@code TenantScopeJpaTransactionManager}). A read with
 * no scope answers no rows — which is why no caller may treat {@code empty()} as "zero" without having stated
 * a tenant, and why the two callers here ({@code ProductVariantService} and {@code InventoryService}) both run
 * inside a boundary that derives the tenant from the store, never from the request.</p>
 */
@Repository
public interface StockPositionRepository
        extends org.springframework.data.repository.Repository<StockPosition, StockPosition.Key> {

    /**
     * How much of {@code productVariantId} this store holds, as the ledger's projection states it — or empty
     * when the ledger has never moved that key.
     *
     * <p>Returned as the scalar the callers actually need instead of the {@link StockPosition} row: the two
     * callers are a response field and a sale's availability gate, and neither has any use for
     * {@code updated_at}. It is the same query for both, which is the point — one place decides what "how much
     * is in this store" means.</p>
     */
    @Query("""
            SELECT p.quantity FROM StockPosition p
            WHERE p.companyStoreId = :companyStoreId
              AND p.productVariantId = :productVariantId
            """)
    Optional<BigDecimal> findQuantity(
            @Param("companyStoreId") UUID companyStoreId, @Param("productVariantId") UUID productVariantId);

    /**
     * The same row as {@link #findQuantity}, read under a pessimistic write lock ({@code SELECT … FOR UPDATE}),
     * which is what a caller that <b>decides</b> about the quantity needs (T5c of
     * {@code odd/tasks/inventory-ledger.md}).
     *
     * <p><b>Why the gate needs the lock and not just the number.</b> A sale's availability gate reads this row
     * and then writes a movement whose projection trigger updates it, so the decision and the write must be
     * serialized on the same row. Until T5c the only thing serializing two deductions of one key was the legacy
     * {@code product_variant_store_stock} lock taken earlier in {@code InventoryService.applySaleDeduction} — a
     * lock T5b's readers already stopped needing and that the deferred T5d would remove — so the gate was correct
     * by accident of an unrelated lock. Locking the row it decides about makes it correct on its own, and a
     * caller can no longer pass the gate on a quantity another transaction is changing.</p>
     *
     * <p><b>An absent row is not a quantity of zero and takes no lock.</b> The ledger holds no position row for a
     * {@code (store, variant)} nothing has ever moved, and {@code SELECT … FOR UPDATE} cannot lock a row that does
     * not exist. The {@code Optional} is therefore silent about which of the two it means: a caller that
     * <b>authorizes</b> anything from this read has to decide, and the sale gate treats empty as zero and refuses
     * (fail-closed) rather than treating "the ledger never moved this key" as permission to sell it.</p>
     *
     * <p><b>How it behaves with a row it already read.</b> Hibernate resolves the lock by statement, so the row
     * lock is always taken on the database; whether the returned <em>quantity</em> is the value read under that
     * lock depends on this being the transaction's first read of the row, because a managed instance is not
     * refreshed. Every caller must therefore take it before reading the projection by any other means.</p>
     *
     * @see #findQuantity(UUID, UUID) for the non-locking read every display path uses
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p FROM StockPosition p
            WHERE p.companyStoreId = :companyStoreId
              AND p.productVariantId = :productVariantId
            """)
    Optional<StockPosition> findByCompanyStoreIdAndProductVariantIdForUpdate(
            @Param("companyStoreId") UUID companyStoreId, @Param("productVariantId") UUID productVariantId);
}
