package com.lifecontrol.api.inventory.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one place the stock ledger ({@code stock_movement}, {@code stock_allocation}) is written from Java, and
 * the binding of {@link com.lifecontrol.api.inventory.ledger.StockLedgerEngine}'s four entry points to it
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
 * <p>The ledger's unique index is {@code uq_movement_source}, which {@code V73} left as
 * {@code (source_type, source_id, movement_type, store_location_id, write_seq)} over the partial predicate
 * {@code reverses_movement_id IS NULL}. Each of those five columns answers a question the tree really asks,
 * and each of them was added by a slice that measured why: {@code V60} keyed the source and the direction so a
 * replayed write is refused, {@code V63} added the location because a movement names exactly one and a line
 * that draws from two is two facts, and {@code V73} added the write because a line books the same direction at
 * the same location more than once over its life. Three shapes of source are written today, and the fifth
 * column is what makes the second of them legal:</p>
 * <ol>
 *   <li><b>A sale line that spans locations books more than one movement.</b>
 *       {@code StockLedgerEngine.applySaleDeduction} writes one {@code SALE} row per location it drains
 *       (priority location first, then FIFO), and a {@code stock_movement} names exactly one
 *       {@code store_location_id}. The rows share the line id and differ by location, which is what
 *       {@code V63}'s fourth column is for.</li>
 *   <li><b>A line books more than one {@code SALE} over its life, at one location.</b> {@code SalesOrderService}
 *       raises a line's quantity by selling the difference and lowers it by reversing and re-selling
 *       ({@code SalesOrderService:961-981}), always under the same {@code SALES_ORDER_ITEM} reference. The
 *       ledger is append-only, so the first {@code SALE} row is never removed; what tells the two writes apart
 *       is the line's own counter, {@code sales_order_items.write_seq}, taken by a locked bump and stated in
 *       the movement by {@code V73}'s fifth column. That is the identity a sale now books:
 *       {@code source_type = 'SALES_ORDER_ITEM'}, {@code source_id = <the line id>},
 *       {@code write_seq = <the write that produced it>}.</li>
 *   <li><b>A receipt line books on its own line, and a manual edit on its own id.</b> A receipt is immutable, so
 *       a line books exactly once and needs no counter ({@link #RECEIPT_LINE_SOURCE_TYPE}); a manual edit names no
 *       document, so its source is the movement's own id ({@link #MANUAL_ADJUSTMENT_SOURCE_TYPE}). A reversal
 *       names the movement it corrects in {@code reverses_movement_id} and states the sale line as its source.</li>
 * </ol>
 * <p><b>The mirror is gone and this is what replaced it.</b> Until T4 a receipt, a manual edit and a reversal
 * stated {@code INVENTORY_MOVEMENT} — the {@code inventory_movements} row written beside them — because no writer
 * had an identity of its own, and a document id repeated once per line. Every shape above was built while that
 * table existed and none of them needs it now, which is why {@code V75} could drop it: the source of a movement is
 * the document that caused it, and {@code V74} dropped the two legacy {@code stock} columns the same slice
 * retired. A row booked before {@code V73} still carries the mirror's value in {@code source_type}, and its
 * {@code source_id} now names nothing — the drop's own report says how many there are.</p>
 * <p>{@code InventoryLedgerBookingIntegrationTest} pins each of those shapes, and the two cases that used to
 * reach a pre-{@code V73} sale through the mirror now pin that it is unreachable.</p>
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
     * The source type of a {@code RECEIPT}: the {@code goods_receipt_items} row that booked it.
     *
     * <p><b>The line, not the document, and not the mirror.</b> A receipt is immutable — nothing edits or deletes
     * one ({@code GoodsReceiptService:242-246}) — so a line books exactly once and needs no counter, which is why
     * the receipt states {@link #SINGLE_WRITE} rather than counting writes. The document id cannot be the
     * identity: {@code uq_movement_source} does not carry the variant, so a document id would make the second
     * line of every multi-line receipt collide with the first. And the mirror could not be it either, because that
     * row died with {@code inventory_movements}.
     *
     * <p>This is the seam the class javadoc names: a {@link Booking} states its own {@code sourceType}, so a writer
     * leaves the mirror without a second constant being baked into the insert.
     */
    public static final String RECEIPT_LINE_SOURCE_TYPE = "GOODS_RECEIPT_ITEM";

    /**
     * The source type of a manual per-store edit: the movement's own id, because a manual edit names no document.
     *
     * <p><b>Why an edit's own id is not shape (b) of the ledger's identity.</b> Shape (b) — the ledger generating
     * the key for a document line — was refused because it hands I5 to the caller's memory: two writes of one line
     * would be indistinguishable from a retry. A manual edit is the opposite case: nothing retries it, no document
     * states it, and the only thing its key must guarantee is that the row it states is one row. Stating its own
     * id does exactly that, and it is the identity that survives the mirror
     * ({@code odd/tasks/ledger-mirror-retirement.md}, D-2). The two adjustment entry points below make that literal:
     * the id the {@link Booking} states is the id the row gets, so a manual edit has ONE identity and not two.</p>
     */
    public static final String MANUAL_ADJUSTMENT_SOURCE_TYPE = "MANUAL_ADJUSTMENT";

    /**
     * The {@code write_seq} of a source that books at most one row per direction and location.
     *
     * <p>Every writer that books at most one row per source states this value explicitly rather than leaving the
     * column to its default, because the two cases are different facts: a receipt, a manual edit and a reversal
     * are stated once per location and have no counter, whereas a sale line counts its writes and books {@code
     * 1}, {@code 2}, {@code 3}… under the same line id ({@code V73}). Saying {@code 0} here is saying "this
     * source cannot book twice under one location" — and the unique key, not this constant, is what enforces
     * it. It is also the value every row the ledger held before {@code V73} carries, so the backfill of the
     * widening is the column's own default and no row was rewritten to say it.</p>
     */
    public static final long SINGLE_WRITE = 0L;

    /** The ledger's closed movement vocabulary ({@code ck_movement_type}, {@code V60}); outbound ones consume. */
    public static final String RECEIPT = "RECEIPT";

    public static final String SALE = "SALE";
    public static final String SALE_REVERSAL = "SALE_REVERSAL";
    public static final String ADJUSTMENT_IN = "ADJUSTMENT_IN";
    public static final String ADJUSTMENT_OUT = "ADJUSTMENT_OUT";

    /**
     * One fact to book: the key every movement needs, the write it belongs to, and the identity of what caused it.
     *
     * @param companyId the tenant, derived once by the caller from the store that owns the movement
     * @param companyStoreId the store the quantity moves in
     * @param storeLocationId the exact location the quantity moves at — one movement names one location
     * @param productVariantId the variant
     * @param actor the username that caused the fact, when there is one
     * @param sourceType what kind of thing {@code sourceId} names. The caller states it, never this class: a sale
     *     states the line's own discriminator, and a receipt, a manual edit or a reversal states
     *     {@link #RECEIPT_LINE_SOURCE_TYPE} or {@link #MANUAL_ADJUSTMENT_SOURCE_TYPE}. There is deliberately no
     *     default and no overload without it — a default would let a later call site inherit an identity nobody
     *     chose, which is the failure mode the parameter exists to remove
     * @param sourceId the row that states this fact: the sale line for a sale, the {@code goods_receipt_items}
     *     row for a receipt, and the movement's own id for a manual edit (D-2)
     * @param writeSeq which write of that source this movement is: the line's counter for a sale ({@code V73}),
     *     and {@link #SINGLE_WRITE} for a source that books at most one row per direction and location. Stated by
     *     the caller for the same reason as {@code sourceType} — the number is the document's, and this class
     *     never invents one
     */
    public record Booking(
            UUID companyId,
            UUID companyStoreId,
            UUID storeLocationId,
            UUID productVariantId,
            String actor,
            String sourceType,
            UUID sourceId,
            long writeSeq) {}

    /**
     * One {@code SALE} row of a source that no reversal names yet: what the reversal has to name, and the quantity
     * it has to move.
     *
     * @param movementId the ledger row a reversal will name in {@code reverses_movement_id}
     * @param companyStoreId the store the row moved quantity in
     * @param storeLocationId the location it moved it at, which is the grain a reversal balances
     * @param productVariantId the variant
     * @param quantity the quantity the row moved, which is exactly what a reversal of it must move ({@code V61})
     */
    public record OpenSale(
            UUID movementId, UUID companyStoreId, UUID storeLocationId, UUID productVariantId, BigDecimal quantity) {}

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
                                        source_type, source_id, write_seq, reverses_movement_id, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?, ?, ?, ?, ?, ?)
            """;

    /** The engine's allocation writer; the only consumption path, and it refuses an uncoverable quantity. */
    private static final String ALLOCATE = "SELECT allocate_stock_movement(?)";

    private static final String OPERATING_CURRENCY = "SELECT currency FROM companies WHERE id = ?";

    /**
     * Every {@code SALE} row of one source that no reversal names yet.
     *
     * <p>Read by the source the movement carries, which since {@code V73} is the sale line itself, so the answer
     * is the set of writes that line has booked and not given back — one row per write and location, oldest
     * first. {@code reverses_movement_id IS NULL} is the same predicate {@code uq_movement_source} has, and the
     * {@code NOT EXISTS} is {@code uq_movement_one_reversal}'s: a row a reversal already names is not open.</p>
     */
    private static final String OPEN_SALES = """
            SELECT m.id, m.company_store_id, m.store_location_id, m.product_variant_id, m.quantity
              FROM stock_movement m
             WHERE m.source_type = ? AND m.source_id = ? AND m.movement_type = ?
               AND m.reverses_movement_id IS NULL
               AND NOT EXISTS (SELECT 1 FROM stock_movement r WHERE r.reverses_movement_id = m.id)
             ORDER BY m.occurred_at, m.id
            """;

    /**
     * The ledger's own balance per location of one store, in the order the ledger first moved each location.
     *
     * <p><b>Why the projections and not the legacy column.</b> The split of a sale across a store's locations
     * is a read, and from T3 the reader answers from {@code stock_balance} — the projection {@code V61}'s trigger
     * maintains from every movement — instead of {@code product_variant_locations.stock}, which the write path
     * stopped maintaining.</p>
     *
     * <p><b>And why the order no longer comes from the legacy table.</b> This query used to {@code JOIN}
     * {@code product_variant_locations} for one reason only: to read that row's {@code created_at}, the FIFO order
     * the legacy query used. A join for an order is still a dependency — and a load-bearing one, because it is an
     * <em>inner</em> join: the moment the write path stopped creating those rows, every balance vanished from the
     * answer and a sale the position had just authorized was refused by the second check with {@code available 0}.
     * FIFO is a fact about the ledger anyway, so the order is now read from the ledger: the instant each location
     * first moved, then {@code store_location_id} as the deterministic tie-break. That is the same order the
     * legacy rows recorded for every location the backfill and the writers created, and it survives the table.</p>
     */
    private static final String OPEN_BALANCES = """
            SELECT b.store_location_id, b.quantity
              FROM stock_balance b
             WHERE b.product_variant_id = ?
               AND b.store_location_id IN (
                   SELECT sl.id
                     FROM store_locations sl
                     JOIN store_zones sz ON sz.id = sl.store_zone_id
                     JOIN store_areas sa ON sa.id = sz.store_area_id
                    WHERE sa.company_store_id = ?)
             ORDER BY (SELECT min(m.occurred_at)
                         FROM stock_movement m
                        WHERE m.store_location_id = b.store_location_id
                          AND m.product_variant_id = b.product_variant_id) ASC,
                      b.store_location_id ASC
            """;

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
        return book(booking, RECEIPT, quantity, unitCost, currency, null, reason, null);
    }

    /**
     * Books one {@code SALE} row and records what it consumed, in that order and in the caller's
     * transaction: the allocation writer refuses a quantity the unallocated inbound rows cannot cover
     * (invariant I6), which is the second gate a deduction meets after the projection's I8.
     *
     * @return the ledger row's id
     */
    public UUID bookSale(Booking booking, BigDecimal quantity) {
        return bookAndAllocate(booking, SALE, quantity, null);
    }

    /**
     * Books one {@code SALE_REVERSAL} that names the row it reverses. A reversal consumes nothing —
     * {@code V61}'s shape rules refuse a second reversal of the same row, a different quantity, another store
     * and a chain — so nothing is allocated here.
     *
     * @return the ledger row's id
     */
    public UUID bookSaleReversal(Booking booking, BigDecimal quantity, UUID reversesMovementId) {
        return book(booking, SALE_REVERSAL, quantity, null, null, reversesMovementId, null, null);
    }

    /**
     * Books the increase side of a manual per-store edit, at the cost the operator's deployment records.
     *
     * <p>The row IS what its booking states: a manual edit names no document, so its source is its own id (D-2),
     * which is why the id the caller passes in the booking becomes the row's id instead of being drawn here.</p>
     */
    public UUID bookAdjustmentIn(
            Booking booking, BigDecimal quantity, BigDecimal unitCost, String currency, String reason) {
        return book(booking, ADJUSTMENT_IN, quantity, unitCost, currency, null, reason, booking.sourceId());
    }

    /** Books the decrease side of a manual per-store edit and records what it consumed (its own id, D-2). */
    public UUID bookAdjustmentOut(Booking booking, BigDecimal quantity) {
        return bookAndAllocate(booking, ADJUSTMENT_OUT, quantity, booking.sourceId());
    }

    /**
     * The {@code SALE} rows of one source that no reversal names yet, oldest first.
     *
     * <p>This is what a reversal names, and it is read by the source the movements carry instead of by the
     * legacy rows beside them: since {@code V73} a sale's source is the line, so the ledger alone answers "what
     * does this reference still hold open" — the question {@code StockLedgerEngine.applySaleReversal} asks. The
     * old answer, one lookup per legacy row, cannot express a line that booked more than one {@code SALE} at one
     * location, which is exactly the case the fifth key column made legal.</p>
     */
    public List<OpenSale> openSales(String sourceType, UUID sourceId) {
        return jdbcTemplate.query(
                OPEN_SALES,
                (rs, rowNum) -> new OpenSale(
                        rs.getObject("id", UUID.class),
                        rs.getObject("company_store_id", UUID.class),
                        rs.getObject("store_location_id", UUID.class),
                        rs.getObject("product_variant_id", UUID.class),
                        rs.getBigDecimal("quantity")),
                sourceType,
                sourceId,
                SALE);
    }

    /**
     * The quantity the ledger holds at each location of one store for one variant (T3).
     *
     * @param companyStoreId the store whose locations bound the answer
     * @param productVariantId the variant
     * @return one entry per location that holds a projection row, in the legacy FIFO order
     */
    public List<LocationBalance> openBalances(UUID companyStoreId, UUID productVariantId) {
        return jdbcTemplate.query(
                OPEN_BALANCES,
                (rs, rowNum) -> new LocationBalance(
                        rs.getObject("store_location_id", UUID.class), rs.getBigDecimal("quantity")),
                productVariantId,
                companyStoreId);
    }

    /** One location's balance in the ledger: the grain a sale consumes and a reversal credits. */
    public record LocationBalance(UUID storeLocationId, BigDecimal quantity) {}

    /**
     * Whether the ledger holds any movement for a variant, which is the question the measure-unit guard asks.
     *
     * <p>It used to be asked of {@code inventory_movements} and of the legacy store column
     * ({@code ProductVariantService:726,730}). Both answers stop being maintained with the rest of the legacy
     * write path, and a guard that asks a frozen column answers "no history" for a variant that has plenty: the
     * refusal would disappear quietly, which is exactly the failure S7 exists to prevent. The ledger is the
     * history, so the ledger answers.</p>
     */
    public boolean hasMovements(UUID productVariantId) {
        var count = jdbcTemplate.queryForObject(MOVEMENTS_OF_VARIANT, Integer.class, productVariantId);
        return count != null && count > 0;
    }

    private static final String MOVEMENTS_OF_VARIANT = """
            SELECT count(*) FROM stock_movement WHERE product_variant_id = ?
            """;

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

    private UUID bookAndAllocate(Booking booking, String movementType, BigDecimal quantity, UUID movementId) {
        var rowId = book(booking, movementType, quantity, null, null, null, null, movementId);
        var allocations = jdbcTemplate.queryForObject(ALLOCATE, Integer.class, rowId);
        if (allocations == null) {
            throw new IllegalStateException("allocate_stock_movement(" + rowId + ") answered no row, so the "
                    + "consumption of an outbound movement is unknown (ADR-0001 invariant I6)");
        }
        return rowId;
    }

    private UUID book(
            Booking booking,
            String movementType,
            BigDecimal quantity,
            BigDecimal unitCost,
            String currency,
            UUID reversesMovementId,
            String reason,
            UUID movementId) {
        requireTransaction();
        var rowId = movementId == null ? UUID.randomUUID() : movementId;
        jdbcTemplate.update(
                INSERT_MOVEMENT,
                rowId,
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
                booking.writeSeq(),
                reversesMovementId,
                reason);
        return rowId;
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
