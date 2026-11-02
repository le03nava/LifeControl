package com.lifecontrol.api.inventory.ledger;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one place the stock ledger ({@code stock_movement}, {@code stock_allocation}) is written from Java, and
 * the binding of {@link com.lifecontrol.api.inventory.service.InventoryService}'s four entry points to it
 * (T5a of {@code odd/tasks/inventory-ledger.md}).
 *
 * <h2>What this class is for, and what it deliberately is not</h2>
 * <p>ADR-0001 decision 1 says every change of quantity is one {@code stock_movement} row. This component
 * inserts that row, and for an outbound row it calls the engine's own allocation writer
 * ({@code allocate_stock_movement}, {@code V61}) — it never re-implements consumption, projections or
 * valuation, which the trigger and the SQL functions own. It also holds no arithmetic: it is handed a fact
 * the legacy engine has just decided and records the same fact in the ledger, in the same transaction.</p>
 *
 * <h2>Where a movement's {@code source_id} comes from, per writer — the decision this class exists to state</h2>
 * <p>The ledger's unique index is {@code uq_movement_source (source_type, source_id, movement_type)} and
 * {@code V60}'s own comment reads "one movement per source document line and direction". Measured against the
 * call sites, that comment cannot be satisfied by the document's or the line's id, in three shapes the tree
 * already has:</p>
 * <ol>
 *   <li><b>A sale line that spans locations books more than one movement.</b>
 *       {@code InventoryService.applySaleDeduction} writes one {@code SALE} row per location it drains
 *       (priority location first, then FIFO), and a {@code stock_movement} names exactly one
 *       {@code store_location_id}. Two rows of one line therefore share the line id.</li>
 *   <li><b>A line books more than one {@code SALE} over its life.</b> {@code SalesOrderService} raises a
 *       line's quantity by selling the difference and lowers it by reversing and re-selling
 *       ({@code SalesOrderService:989}, {@code :991-992}), always under the same {@code SALES_ORDER_ITEM}
 *       reference. The ledger is append-only, so the first {@code SALE} row is never removed, and the second
 *       one under the same line id would be refused.</li>
 *   <li><b>A receipt's writer is handed the document, not the line.</b> {@code GoodsReceiptService:269}
 *       passes {@code saved.getId()} — the {@code goods_receipts} row — once per line, so any receipt with two
 *       lines books two {@code RECEIPT} movements under one id.</li>
 * </ol>
 * <p>The identity this class uses instead is the row that states the fact in the dual-write window: the
 * {@code inventory_movements} row {@code InventoryService} writes beside the ledger row, one fact per row, so
 * {@code source_type = 'INVENTORY_MOVEMENT'} and {@code source_id = <that row's id>}. It is unique by
 * construction (it is a primary key), it makes the ledger a one-to-one mirror of the legacy ledger — which is
 * what lets a test see the two copies agree, the whole point of this slice — and it is the same shape
 * {@code V62} established for the backfill, where {@code source_id} is the {@code product_variant_locations}
 * row's own id rather than a document's.</p>
 * <p><b>What that costs, stated rather than hidden.</b> The document identity ({@code reference_type} /
 * {@code reference_id}) stays on the legacy row, so the ledger alone cannot yet answer "which document line
 * caused this". Closing that needs a migration this slice may not write: {@code uq_movement_source} has to
 * gain {@code store_location_id} (shape 1) and the source identity has to become the <em>transition</em>
 * rather than the line (shape 2), after which {@code source_type}/{@code source_id} can carry the document
 * line and a per-transition key. Until then the mirror is the honest answer, and
 * {@code InventoryLedgerBookingIntegrationTest} pins both colliding shapes so the gap is measured instead of
 * discovered.</p>
 *
 * <h2>The order chosen: the legacy write first, the ledger row immediately after it</h2>
 * <p>Both runs in one transaction, so the outcome of a divergence is the same either way: everything rolls
 * back. The order still matters for <em>which</em> refusal a caller sees first.</p>
 * <ul>
 *   <li>The legacy engine's availability checks ({@code storeStock} aggregate, then the location sum) are the
 *       documented contract of a sale — an {@code InsufficientStockException} (409) — and they run before any
 *       write. Booking the ledger first would let the projection trigger's invariant I8 preempt that contract
 *       with a database error the API never documented.</li>
 *   <li>The ledger's I8 refusal stays an independent second gate, at store grain, exactly as
 *       {@code V61} designed it: it does not read the legacy numbers, so a ledger that is behind the legacy
 *       balance refuses the movement instead of agreeing with a divergence.</li>
 *   <li>A reversal has to name the {@code SALE} row it reverses, and that row is written by the same loop
 *       iteration that wrote the legacy row — the ledger write can only follow it.</li>
 * </ul>
 *
 * <h2>Tenant scope, and why a missing one is loud</h2>
 * <p>The five ledger tables are armed on {@code tenant_table} ({@code V60}) and {@code FORCE}d, so these
 * statements run as {@code lifecontrol_app} under ADR-0005's policy and need the transaction's scope. This
 * class never states a scope and never bypasses one: it joins the caller's transaction
 * ({@link #requireTransaction()}) whose boundary installed the scope ({@code TenantScopeJpaTransactionManager}).
 * An insert with no scope is refused by the policy — the platform's own error, not a silent no-op — and
 * {@link #operatingCurrency(UUID)} turns the read side of the same fact into an explicit refusal instead of
 * letting a {@code NULL} scope answer "no rows".</p>
 *
 * <h2>{@code occurred_at} is the statement's instant, not the transaction's</h2>
 * <p>{@code V60} defaults the column to {@code now()}, which in PostgreSQL is the <em>transaction's</em> start
 * instant: every movement one transaction books would share it, and FIFO — whose order is
 * {@code (occurred_at, id)} — would tie on every row and fall back to a random UUID. The insert states
 * {@code clock_timestamp()} instead, so a row's instant is the instant it was booked and FIFO follows the
 * order the legacy engine recorded its facts in. The explicit value is also why a receipt with two lines of
 * the same variant consumes oldest-first instead of arbitrarily.</p>
 */
@Component
public class StockLedgerWriter {

    /**
     * The {@code stock_movement.source_type} of a movement that mirrors one {@code inventory_movements} row.
     *
     * <p><b>This is a transition shape and it dies with the legacy ledger.</b> During the dual-write window
     * defined by the re-cut entry of {@code odd/tasks/inventory-ledger.md} (the one that splits T5 into T5a,
     * T5b and T5c) the source identity of a movement is the legacy row it mirrors, because neither the document
     * id nor the line id can satisfy {@code uq_movement_source} in the shapes the tree already writes: a sale
     * line that spans locations books one row per location, a line sold again after its reversal books a second
     * {@code SALE} under the same reference, and a receipt hands its writer the document id once per line. It is
     * also V62's own precedent — the backfill's movements carry {@code source_type = 'MIGRATION'} and the id of
     * the row that stated the balance. T5c stops the legacy writes and {@code inventory_movements} drops with
     * T6, at which point {@code source_type}/{@code source_id} must carry the per-transition document identity
     * instead; that is why the source type is a {@link Booking} component the caller states rather than a
     * constant baked into every insert here.</p>
     */
    public static final String MIRRORED_SOURCE_TYPE = "INVENTORY_MOVEMENT";

    /** The ledger's closed movement vocabulary ({@code ck_movement_type}, {@code V60}); outbound ones consume. */
    public static final String RECEIPT = "RECEIPT";

    public static final String SALE = "SALE";
    public static final String SALE_REVERSAL = "SALE_REVERSAL";
    public static final String ADJUSTMENT_IN = "ADJUSTMENT_IN";
    public static final String ADJUSTMENT_OUT = "ADJUSTMENT_OUT";

    /**
     * One fact to book: the key every movement needs, and the identity of what caused it.
     *
     * @param companyId the tenant, derived once by the caller from the store that owns the movement
     * @param companyStoreId the store the quantity moves in
     * @param storeLocationId the exact location the quantity moves at — one movement names one location
     * @param productVariantId the variant
     * @param actor the username that caused the fact, when there is one
     * @param sourceType what kind of thing {@code sourceId} names. The caller states it, never this class:
     *     every production call site passes {@link #MIRRORED_SOURCE_TYPE} during the dual-write window, and a
     *     fixture opening passes its own discriminator. There is deliberately no default and no overload
     *     without it — a default would let a later call site fall back into the mirror silently, which is the
     *     failure mode the parameter exists to remove
     * @param sourceId the row that states this fact: the {@code inventory_movements} row the movement mirrors,
     *     or — for a fixture opening — the {@code product_variant_locations} row whose balance it opens (see
     *     the class javadoc)
     */
    public record Booking(
            UUID companyId,
            UUID companyStoreId,
            UUID storeLocationId,
            UUID productVariantId,
            String actor,
            String sourceType,
            UUID sourceId) {}

    /**
     * One row of the ledger, without its key: what {@code V60} declares and nothing else.
     *
     * <p>{@code unit_cost} and {@code currency} are required by {@code ck_movement_inbound_cost} on every row
     * that adds quantity and are {@code null} on every row that removes it, where the cost of the consumed
     * units lives in {@code stock_allocation.unit_cost} instead (decision 4).</p>
     */
    private static final String INSERT_MOVEMENT = """
            INSERT INTO stock_movement (id, company_id, company_store_id, store_location_id, product_variant_id,
                                        movement_type, quantity, unit_cost, currency, occurred_at, actor,
                                        source_type, source_id, reverses_movement_id, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?, ?, ?, ?, ?)
            """;

    /** The engine's allocation writer; the only consumption path, and it refuses an uncoverable quantity. */
    private static final String ALLOCATE = "SELECT allocate_stock_movement(?)";

    private static final String OPERATING_CURRENCY = "SELECT currency FROM companies WHERE id = ?";

    /** The ledger row one legacy row was mirrored into, which is what a reversal has to name. */
    private static final String MIRRORED_MOVEMENT =
            "SELECT id FROM stock_movement WHERE source_type = ? AND source_id = ? AND movement_type = ?";

    private static final String REVERSAL_OF = "SELECT count(*) FROM stock_movement WHERE reverses_movement_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public StockLedgerWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Books an inbound movement: the quantity arrives, so it must state what it cost (decision 4).
     *
     * @return the ledger row's id
     */
    public UUID bookReceipt(Booking booking, BigDecimal quantity, BigDecimal unitCost, String currency, String reason) {
        return book(booking, RECEIPT, quantity, unitCost, currency, null, reason);
    }

    /**
     * Books one {@code SALE} row and records what it consumed, in that order and in the caller's
     * transaction: the allocation writer refuses a quantity the unallocated inbound rows cannot cover
     * (invariant I6), which is the second gate a deduction meets after the projection's I8.
     *
     * @return the ledger row's id
     */
    public UUID bookSale(Booking booking, BigDecimal quantity) {
        return bookAndAllocate(booking, SALE, quantity);
    }

    /**
     * Books one {@code SALE_REVERSAL} that names the row it reverses. A reversal consumes nothing —
     * {@code V61}'s shape rules refuse a second reversal of the same row, a different quantity, another store
     * and a chain — so nothing is allocated here.
     *
     * @return the ledger row's id
     */
    public UUID bookSaleReversal(Booking booking, BigDecimal quantity, UUID reversesMovementId) {
        return book(booking, SALE_REVERSAL, quantity, null, null, reversesMovementId, null);
    }

    /** Books the increase side of a manual per-store edit, at the cost the operator's deployment records. */
    public UUID bookAdjustmentIn(
            Booking booking, BigDecimal quantity, BigDecimal unitCost, String currency, String reason) {
        return book(booking, ADJUSTMENT_IN, quantity, unitCost, currency, null, reason);
    }

    /** Books the decrease side of a manual per-store edit and records what it consumed. */
    public UUID bookAdjustmentOut(Booking booking, BigDecimal quantity) {
        return bookAndAllocate(booking, ADJUSTMENT_OUT, quantity);
    }

    /**
     * The ledger row one {@code inventory_movements} row was mirrored into.
     *
     * <p>Read by the reversal path, which needs the {@code SALE} row's id to name in
     * {@code reverses_movement_id}. Empty means the legacy row predates this slice's writers: a sale booked
     * before the deploy has no ledger movement, and the caller refuses instead of inventing one.</p>
     */
    public Optional<UUID> mirroredMovementId(UUID sourceId, String movementType) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    MIRRORED_MOVEMENT, UUID.class, MIRRORED_SOURCE_TYPE, sourceId, movementType));
        } catch (EmptyResultDataAccessException absent) {
            return Optional.empty();
        }
    }

    /** Whether a reversal already names this movement ({@code uq_movement_one_reversal} allows one). */
    public boolean isReversed(UUID movementId) {
        var reversals = jdbcTemplate.queryForObject(REVERSAL_OF, Integer.class, movementId);
        return reversals != null && reversals > 0;
    }

    /**
     * The operating currency of a company — the only currency a document can be in (ADR-0002 decision 2),
     * and therefore what an inbound movement's {@code unit_cost} is denominated in.
     *
     * <p>{@code companies} is armed by ADR-0005 and a transaction with no scope answers <b>no</b> rows. That
     * read is turned into a refusal that names the cause rather than into a {@code NULL} currency the
     * {@code ck_movement_inbound_cost} check would report far from its origin.</p>
     *
     * @throws IllegalStateException when the company is not visible, which means the transaction carries a
     *     different tenant's scope — or none
     */
    public String operatingCurrency(UUID companyId) {
        try {
            var currency = jdbcTemplate.queryForObject(OPERATING_CURRENCY, String.class, companyId);
            if (currency == null) {
                throw new IllegalStateException("Company " + companyId + " states no operating currency, so an "
                        + "inbound movement cannot denominate what it cost (ADR-0001 decision 4)");
            }
            return currency;
        } catch (EmptyResultDataAccessException absent) {
            throw new IllegalStateException(
                    "Company " + companyId + " is not visible to this transaction, so no "
                            + "stock movement can be booked for it: the ledger's tables are armed on tenant_table (V60) "
                            + "and the transaction must carry the scope ADR-0005 installs at its boundary",
                    absent);
        }
    }

    private UUID bookAndAllocate(Booking booking, String movementType, BigDecimal quantity) {
        var movementId = book(booking, movementType, quantity, null, null, null, null);
        var allocations = jdbcTemplate.queryForObject(ALLOCATE, Integer.class, movementId);
        if (allocations == null) {
            throw new IllegalStateException("allocate_stock_movement(" + movementId + ") answered no row, so the "
                    + "consumption of an outbound movement is unknown (ADR-0001 invariant I6)");
        }
        return movementId;
    }

    private UUID book(
            Booking booking,
            String movementType,
            BigDecimal quantity,
            BigDecimal unitCost,
            String currency,
            UUID reversesMovementId,
            String reason) {
        requireTransaction();
        var movementId = UUID.randomUUID();
        jdbcTemplate.update(
                INSERT_MOVEMENT,
                movementId,
                booking.companyId(),
                booking.companyStoreId(),
                booking.storeLocationId(),
                booking.productVariantId(),
                movementType,
                quantity,
                unitCost,
                currency,
                booking.actor(),
                booking.sourceType(),
                booking.sourceId(),
                reversesMovementId,
                reason);
        return movementId;
    }

    /**
     * Refuses to write outside a transaction: a statement that borrowed its own connection would commit the
     * ledger row while the legacy write it mirrors is still pending, which is the one state this slice exists
     * to make impossible.
     */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A stock movement has to be booked inside the transaction that moves "
                    + "the legacy balance, or the two copies can disagree after a rollback");
        }
    }
}
