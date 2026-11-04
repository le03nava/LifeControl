package com.lifecontrol.api.inventory.service;

import com.lifecontrol.api.inventory.exception.InsufficientStockException;
import com.lifecontrol.api.inventory.exception.StoreInventorySettingsNotFoundException;
import com.lifecontrol.api.inventory.ledger.StockLedgerWriter;
import com.lifecontrol.api.inventory.model.InventoryMovement;
import com.lifecontrol.api.inventory.model.MovementType;
import com.lifecontrol.api.inventory.model.ProductVariantLocation;
import com.lifecontrol.api.inventory.model.StockPosition;
import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import com.lifecontrol.api.inventory.repository.ProductVariantLocationRepository;
import com.lifecontrol.api.inventory.repository.StockPositionRepository;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.service.CompanyStoreOwnerResolver;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory write path: moves the per-location balance and records the movement that explains it.
 *
 * <p>This service is the single owner of the stock arithmetic. Four writers call it: the goods
 * receipt ({@link #applyReceipt}, from {@code GoodsReceiptService}), the sale and its reversal
 * ({@link #applySaleDeduction} / {@link #applySaleReversal}, from {@code SalesOrderService}), and
 * the manual per-store edit ({@link #applyStockAdjustment}, from
 * {@code ProductVariantService.upsertStoreStock}).</p>
 *
 * <h2>Lock order after the variant-identity split</h2>
 * <p>The variant definition no longer carries stock, so it is not locked: the serialization point
 * is the <strong>per-store stock row</strong> ({@code product_variant_store_stock}), taken through
 * {@code ProductVariantStoreStockRepository#findByProductVariantIdAndCompanyStoreIdForUpdate}. A
 * receipt takes, in order, <strong>storeStock &rarr; locationBalance</strong>; the goods-receipt use
 * case additionally takes the purchase-order lock ahead of both, giving the global order
 * {@code purchaseOrder -> storeStock -> locationBalance}. Two receipts that share a store row
 * serialize there; nothing takes these locks in the opposite order, so the order is cycle-free. The
 * variant definition is read without a lock only to reject a variant that is missing, disabled or
 * owned by another company than the one that owns the store.</p>
 *
 * <h2>Stock contract: every writer moves both sides</h2>
 * <p>Sales is location-aware. {@code SalesOrderService} routes every deduction and every restoration
 * through this service, so a sale moves the per-store aggregate <em>and</em> the per-location
 * balances in the same transaction, exactly as {@link #applyReceipt} and
 * {@link #applyStockAdjustment} do. The invariant</p>
 * <pre>{@code product_variant_store_stock.stock = SUM(product_variant_locations.stock)}</pre>
 * <p>therefore holds by construction for every mutation (W3-D5), and
 * {@code V15__inventory_balance_reset.sql} removed the divergence that predated it. The ledger is
 * complete: {@link MovementType#RECEIPT}, {@link MovementType#SALE},
 * {@link MovementType#SALE_REVERSAL}, {@link MovementType#ADJUSTMENT_INCREASE} and
 * {@link MovementType#ADJUSTMENT_DECREASE} explain every balance change (W3-D7).</p>
 *
 * <h2>Why {@link #applyReceipt} stays additive</h2>
 * <p>The receipt increments the location balance and the store's sellable row, and neither is
 * derived from the other. With every writer moving both sides this is correct by construction
 * rather than the compromise it once was, and the switch to recompute semantics is closed (W3-D5):
 * while the invariant holds a recompute would have nothing to correct. It must stay that way. The
 * one operation the receipt may never perform is deriving the aggregate from
 * {@code SUM(product_variant_locations.stock)}: if a writer ever leaves the two sides disagreeing,
 * that derivation silently resurrects stock a sale already sold instead of surfacing the
 * divergence. The receipt explains its own delta; it does not repair another writer's balances.</p>
 *
 * <h2>The ledger is booked beside the legacy write (T5a of {@code odd/tasks/inventory-ledger.md})</h2>
 * <p>ADR-0001's step 4 cannot run before step 6 (the ADR records why), so the executable order is
 * <em>write, read, stop writing, drop</em>: this class keeps writing the legacy balances exactly as it did
 * and, in the same transaction, books the same fact into {@code stock_movement} through
 * {@link StockLedgerWriter}. Nothing else moves — no reader changes here, no legacy write is deleted, and the
 * four public methods keep their signatures, so {@code GoodsReceiptService}, {@code SalesOrderService} and
 * {@code ProductVariantService} are untouched. The point of the duplication is that the two copies can be
 * seen to agree, which is the only moment a defect in either one is visible;
 * {@code InventoryLedgerBookingIntegrationTest} is the witness.</p>
 *
 * <p><b>The order, and why.</b> The ledger row is written <em>after</em> the legacy write it mirrors, inside
 * the same loop iteration, in the same transaction. Three reasons, and each is load-bearing:</p>
 * <ol>
 *   <li>The legacy availability checks ({@code storeStock} aggregate, then the location sum) are the
 *       documented sale contract — {@link InsufficientStockException}, a 409 — and they run before any write.
 *       Booking the ledger first would let the projection trigger's invariant I8 preempt that contract with a
 *       database error the API never promised.</li>
 *   <li>I8 stays an <em>independent</em> second gate at store grain, exactly as {@code V61} built it: it reads
 *       the ledger's own position, so a ledger that is behind the legacy balance refuses the movement rather
 *       than agreeing with a divergence. Because both writes share one transaction, a refusal there rolls the
 *       legacy write back too.</li>
 *   <li>A reversal has to name the {@code SALE} row it reverses ({@code reverses_movement_id}), and that row
 *       is written by the same iteration that writes the legacy row.</li>
 * </ol>
 *
 * <p><b>What the ledger consumes.</b> Every outbound row ({@code SALE}, {@code ADJUSTMENT_OUT}) is followed
 * by {@code allocate_stock_movement}, so the consumption is recorded rather than recomputed (decision 3) and
 * invariant I6 holds from the first row this class books. A {@code SALE_REVERSAL} names the row it reverses
 * and allocates nothing: the rows it frees are the ones that row consumed.</p>
 *
 * <h2>T5b: the position is the authority a sale is refused by</h2>
 * <p>The readers moved onto {@code stock_position} in T5b — the store-scoped list, the POS search and the
 * store-stock response all answer the projection — and a sale is the one caller for which "the readers" and
 * "the gate" are the same question. {@link #applySaleDeduction} therefore reads the position as a third
 * fail-closed check, after the two legacy ones and before any write, and answers its refusal as
 * {@link InsufficientStockException}: the contract the Angular POS already handles, with the message shape it
 * already reads. What it replaces is the 500 the API produced when {@code V61}'s invariant I8 refused an
 * outbound movement, which was the ledger saying the same thing in a language no client had agreed to. The
 * legacy writes stay exactly where they were — this slice moves no write — and the ledger's I8 remains its own
 * independent gate and lock.</p>
 *
 * <h2>T5c: the gate takes the projection row's lock</h2>
 * <p>The gate above read {@code stock_position} with a plain read, so what actually serialized two concurrent
 * deductions of one key was the legacy {@code product_variant_store_stock} lock taken in step 3 — a lock T5b's
 * readers already stopped needing and that the deferred T5d would remove, which would have left the gate deciding
 * on a number another transaction is free to change. T5c makes step 4b a locking read
 * ({@code StockPositionRepository#findByCompanyStoreIdAndProductVariantIdForUpdate}), so the gate serializes on
 * the row it decides about and reads the quantity under that lock. A key with no position row takes no lock and is
 * refused rather than authorized: the ledger never moved it, and an empty read is used as zero on purpose. The
 * lock order grows one level — {@code storeStock -> stockPosition -> locationBalance} — and stays cycle-free,
 * because no path anywhere takes the position before the store row of the same key. {@code V61}'s trigger keeps
 * its own {@code FOR UPDATE} on the same row as the ledger's last gate for every writer that reaches the table:
 * the two are not the same lock and neither is redundant — this one turns the refusal into the API's 409 before a
 * write, and that one holds in the database whether or not this gate ran.</p>
 */
@Service
@Transactional
public class InventoryService {

    private static final Logger logger = LoggerFactory.getLogger(InventoryService.class);

    /**
     * Ledger source discriminator of a manual per-store stock edit (W3-D14). There is no
     * {@code referenceId}: a hand edit has no source document, so the movement's actor, timestamp
     * and location are the whole explanation.
     */
    private static final String MANUAL_STOCK_EDIT_REFERENCE_TYPE = "MANUAL_STOCK_EDIT";

    /**
     * Why an inbound movement can be booked at zero cost: {@code ck_movement_inbound_cost} demands a cost and
     * a currency on every row that adds quantity, and {@code product_variant_store_stock.cost_price} is the
     * only cost this deployment records. A row with no recorded cost is booked at zero with the reason stated
     * on the row, exactly as {@code V62}'s backfill decided for the same fact.
     */
    private static final String UNRECORDED_COST_REASON =
            "no cost is recorded for this variant in this store, so the inbound movement is booked at 0";

    private final ProductVariantRepository productVariantRepository;
    private final ProductVariantStoreStockRepository productVariantStoreStockRepository;
    private final ProductVariantLocationRepository productVariantLocationRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final StoreInventorySettingsRepository storeInventorySettingsRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CompanyStoreOwnerResolver companyStoreOwnerResolver;
    private final StockLedgerWriter stockLedgerWriter;
    private final StockPositionRepository stockPositionRepository;

    public InventoryService(
            ProductVariantRepository productVariantRepository,
            ProductVariantStoreStockRepository productVariantStoreStockRepository,
            ProductVariantLocationRepository productVariantLocationRepository,
            InventoryMovementRepository inventoryMovementRepository,
            StoreInventorySettingsRepository storeInventorySettingsRepository,
            CompanyStoreRepository companyStoreRepository,
            CompanyStoreOwnerResolver companyStoreOwnerResolver,
            StockLedgerWriter stockLedgerWriter,
            StockPositionRepository stockPositionRepository) {
        this.productVariantRepository = productVariantRepository;
        this.productVariantStoreStockRepository = productVariantStoreStockRepository;
        this.productVariantLocationRepository = productVariantLocationRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.storeInventorySettingsRepository = storeInventorySettingsRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.companyStoreOwnerResolver = companyStoreOwnerResolver;
        this.stockLedgerWriter = stockLedgerWriter;
        this.stockPositionRepository = stockPositionRepository;
    }

    /**
     * Adds {@code quantity} of {@code productVariantId} to the stock held at {@code storeLocationId}
     * and to the store's sellable row, appending one {@code RECEIPT} movement to the ledger.
     *
     * @param quantity signed amount received; must be greater than zero
     * @param referenceType free-form source discriminator (the goods receipt in W2c)
     * @param referenceId identity of that source, kept so the ledger can be traced back to it
     * @param createdBy username of the receiver, when known
     * @throws IllegalArgumentException when {@code quantity} is not greater than zero, or when the
     *     variant has no stock row in {@code companyStoreId}
     * @throws ProductVariantNotFoundException when the variant does not exist, is disabled, or
     *     belongs to a company other than the one that owns {@code companyStoreId}
     * @throws CompanyStoreNotFoundException when the store does not exist, so no company can be
     *     derived from it
     */
    public void applyReceipt(
            UUID productVariantId,
            UUID companyStoreId,
            UUID storeLocationId,
            BigDecimal quantity,
            String referenceType,
            UUID referenceId,
            String createdBy) {

        // 1. Reject the invalid quantity before touching the database: an invalid receipt must not
        //    lock a store row, create a balance row or leave a ledger entry behind.
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Receipt quantity must be greater than zero, but was " + quantity);
        }

        // 2. The definition must exist, be enabled and belong to the company that owns the store. It
        //    is read WITHOUT a lock: the definition carries no stock, so it is not the serialization
        //    point any more. The company predicate is what keeps a receipt from moving another
        //    tenant's stock: the store argument says which tenant may be touched, while the variant id
        //    says nothing at all, and the schema cannot forbid the pair because company_stores has no
        //    company_id. The enabled filter preserves the old ProductVariantNotFoundException
        //    contract.
        if (productVariantRepository
                .findByIdAndCompanyIdAndEnabledTrue(productVariantId, companyIdOf(companyStoreId))
                .isEmpty()) {
            throw new ProductVariantNotFoundException(productVariantId);
        }

        // 2b. The tenant every booked movement carries, derived once from the store that owns it. The
        //     ledger's tables are armed on tenant_table (V60), so the transaction this method runs in
        //     must already carry that company's scope; a movement for another company is refused by the
        //     policy rather than written, which is the loud half of ADR-0005.
        var companyId = companyIdOf(companyStoreId);

        // 3. Lock the per-store stock row. This is the FIRST lock of the transaction and the
        //    serialization point of the whole write: two receipts for the same variant in the same
        //    store queue here, and two receipts for the same variant in DIFFERENT stores do not
        //    (they no longer share the definition row). An absent row is the store-membership
        //    failure: the variant was never registered in this store, so there is nothing to
        //    attribute the receipt to. Nothing has been written yet.
        var storeStock = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreIdForUpdate(productVariantId, companyStoreId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product variant " + productVariantId + " has no stock row in store " + companyStoreId));

        // 4. Only now acquire the location balance. The conflict-tolerant insert creates the row when
        //    it is missing; it is not required for cycle safety under storeStock-first ordering, but
        //    it stays a cheap defence and spares any future caller that skips the lock from the
        //    unique-violation-aborts-the-transaction trap. The locking select that follows is the
        //    FIRST read of this entity in the transaction, so it cannot return a stale instance.
        productVariantLocationRepository.insertBalanceIfAbsent(productVariantId, storeLocationId);
        lockBalance(productVariantId, storeLocationId);

        // 5b. THE SAME FACT, IN THE LEDGER, BESIDE IT. One RECEIPT row that adds the quantity this line
        //     received, at the cost this store records for the variant and in the company's operating
        //     currency — ck_movement_inbound_cost (V60) requires both on every row that adds quantity.
        //     The projection trigger derives stock_balance, stock_position and stock_valuation from it,
        //     so from this row on the ledger's numbers are the legacy numbers.
        //     The source is the caller's pair, not the mirrored row's id: a receipt line is its own identity
        //     (StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE), and naming the mirror here is what kept the
        //     ledger's receipt identity dependent on a table this unit drops.
        bookLedgerInbound(
                referenceType,
                companyId,
                companyStoreId,
                storeLocationId,
                productVariantId,
                quantity,
                storeStock.getCostPrice(),
                referenceId,
                createdBy,
                true);

        // 6. AND THAT IS THE WHOLE WRITE. The projections (stock_balance, stock_position, stock_valuation) are
        //    the trigger's, derived from the movement above; the legacy location and store columns are no longer
        //    maintained here and the migration after this one drops them
        //    (odd/tasks/ledger-mirror-retirement.md, S4). The two rows are still locked — store first, then the
        //    location — because the lock order is what every mover agrees on, and taking it stays free while the
        //    rows exist.
    }

    /**
     * Deducts {@code quantity} as the reference's <b>only</b> write, for a caller that owns no counter.
     *
     * <p>This is the engine-level entry point the sale-batch path does not use: {@link SaleChange} carries the
     * line's own {@code writeSeq} and {@link #applySaleBatch} hands it to the seven-argument overload. A caller
     * whose reference books at most one movement per direction and location reaches the same code through this
     * shape, and it says {@link StockLedgerWriter#SINGLE_WRITE} explicitly rather than leaving the column to its
     * default. A document line that can book more than once must state its sequence: the second write of one
     * reference under {@code 0} is refused by {@code uq_movement_source}, loudly, which is the intended answer to
     * a caller that forgot.</p>
     */
    public void applySaleDeduction(
            UUID productVariantId,
            UUID companyStoreId,
            BigDecimal quantity,
            String referenceType,
            UUID referenceId,
            String createdBy) {
        applySaleDeduction(
                productVariantId,
                companyStoreId,
                quantity,
                referenceType,
                referenceId,
                createdBy,
                StockLedgerWriter.SINGLE_WRITE);
    }

    /**
     * Deducts {@code quantity} of {@code productVariantId} from {@code companyStoreId} and appends
     * one {@code SALE} movement per store location consumed, all with a positive quantity (W3-D7).
     *
     * <p>The store's priority location is {@code store_inventory_settings.sales_location_id}. The
     * quantity drains there first and the remainder spills over the store's other balance rows in
     * FIFO order (the query order of
     * {@link ProductVariantLocationRepository#findByProductVariantIdAndCompanyStoreId(UUID, UUID)}:
     * oldest row first, {@code store_location_id} as the deterministic tie-break). A store with no
     * settings row falls back to plain FIFO and logs exactly one warning (W3-D8): the precondition
     * of a sale is the per-store aggregate, already validated below, so a missing configuration row
     * must not become a store-wide sales outage. A priority location that holds no balance of this
     * variant is simply absent from the allocation and needs no warning.</p>
     *
     * <p>Failure is closed twice (W3-D1). The aggregate — the sellable number the operator sees and
     * the precondition a sale has today — is checked first against the locked store row; then the
     * location rows are checked together, so a state the reconciliation should have removed cannot
     * leave a store partially deducted. Both checks run before any write and both raise
     * {@link InsufficientStockException}. A store that does not stock the variant at all never
     * reaches the locks: the absent row is zero sellable stock and raises the same exception, while
     * {@link #applyReceipt} keeps rejecting that same absent row — see the asymmetry at step 3.</p>
     *
     * <p><b>And it is closed a third time, by the ledger's position (T5b).</b> ADR-0001 decision 1 makes
     * {@code stock_position} the only authority on quantity, so a sale is refused by the position — and that
     * refusal is the same {@link InsufficientStockException} with the same message, not the database error
     * {@code V61}'s invariant I8 raises. The two copies agree while both writers run, so in a consistent tree
     * the position gate can only agree with the two legacy ones; where they disagree — the state {@code V62}
     * reports and a legacy column edited by hand produces — the position wins and the operator sees the 409 the
     * sale contract documents.</p>
     *
     * <p><b>And, from T5c, that gate holds the row's own lock.</b> Step 4b reads {@code stock_position}
     * {@code FOR UPDATE}, so two deductions of one key serialize on the row the decision is about instead of on
     * the legacy store row locked in step 3. That matters because the store row's lock is an accident of the
     * dual-write window — T5b's readers already stopped needing it and T5d is the slice that would remove it —
     * and a gate that is only correct while an unrelated lock is held is a gate that fails silently when that
     * lock goes. The absent-row case is fail-closed rather than optimistic: no lock can be taken on a row that
     * does not exist, and a key the ledger never moved has no quantity to sell, so the empty read is used as zero
     * and refuses. {@code V61}'s I8 keeps its own {@code FOR UPDATE} on the same row as the ledger's last gate for
     * every writer that can reach the table; two locks inside one transaction are one lock on the database, and
     * what is not duplicated is the audience — this one answers the API's documented 409 before anything is
     * written, and that one holds whether or not this gate ran.</p>
     *
     * <p>Lock order grows one level: the definition is read without a lock, then
     * {@code storeStock -> stockPosition -> locationBalance}. The shared {@code storeStock} row is the first lock every
     * mover of this variant and store takes, so two movers are serialized before either of them reaches a
     * balance row, and the balance rows they can then contend for all belong to that same
     * {@code (variant, store)} pair. The order in which balances are taken therefore cannot invert between
     * two movers: this method walks them in FIFO order, the reversal walks them by
     * {@code store_location_id}, and neither order can meet the other.</p>
     *
     * @param quantity signed amount sold; must be greater than zero
     * @param referenceType source discriminator carrying the line-level provenance of the sale (W3-D6), and the
     *     {@code source_type} the ledger movement states: the same vocabulary the legacy row carries, so the
     *     ledger alone answers which line caused a movement
     * @param referenceId identity of that source, which is the {@code source_id} of every movement this deduction
     *     books
     * @param createdBy username of the seller, when known
     * @param writeSeq which write of that source this deduction is ({@code sales_order_items.write_seq},
     *     {@code V73}); the ledger keys the movement on it, so two writes of one line are two legal rows
     * @throws IllegalArgumentException when {@code quantity} is not greater than zero
     * @throws InsufficientStockException when the store cannot cover {@code quantity} — including
     *     when the store does not stock the variant at all, which is zero sellable stock — first
     *     against the aggregate, then against the sum of the store's location balances, and from T5b also
     *     against the ledger's position, which is the authority ADR-0001 decision 1 makes
     * @throws ProductVariantNotFoundException when the variant does not exist, is disabled, or
     *     belongs to a company other than the one that owns {@code companyStoreId}
     * @throws CompanyStoreNotFoundException when the store does not exist, so no company can be
     *     derived from it
     */
    public void applySaleDeduction(
            UUID productVariantId,
            UUID companyStoreId,
            BigDecimal quantity,
            String referenceType,
            UUID referenceId,
            String createdBy,
            long writeSeq) {

        // 1. Reject the invalid quantity before touching the database, as applyReceipt does: an
        //    invalid sale must not lock a store row or leave a ledger entry behind.
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Sale quantity must be greater than zero, but was " + quantity);
        }

        // 2. The definition must exist, be enabled and belong to the store's company, read WITHOUT a
        //    lock: it carries no stock. Asking by bare id would let a sale of one tenant's store drain
        //    a balance row that names another tenant's variant — a pair the schema cannot forbid, since
        //    company_stores carries no company_id and the store stock row is just (variant, store).
        if (productVariantRepository
                .findByIdAndCompanyIdAndEnabledTrue(productVariantId, companyIdOf(companyStoreId))
                .isEmpty()) {
            throw new ProductVariantNotFoundException(productVariantId);
        }

        // 2b. The tenant every booked movement carries, derived once from the store that owns it (see
        //     applyReceipt step 2b).
        var companyId = companyIdOf(companyStoreId);

        // 3. FIRST lock: the per-store stock row, the serialization point of every stock mover. A
        //    store that does not stock the variant has ZERO sellable stock, so the sale fails
        //    closed with the same InsufficientStockException (409) a zero aggregate produces. This
        //    is deliberately asymmetric with applyReceipt, which rejects the missing row as an
        //    invalid receipt: receiving into a store the variant is not stocked in is a placement
        //    error, while a sale for an unstocked variant is an empty balance, and it must keep the
        //    sales contract (409) rather than surface as the 400 an IllegalArgumentException gets.
        // The lock is the point of this read and the only reason it stays (S4): two sales of one
        // (store, variant) queue here, and the row's own number is nobody's source any more.
        productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreIdForUpdate(productVariantId, companyStoreId)
                .orElseThrow(() -> new InsufficientStockException(productVariantId, quantity, BigDecimal.ZERO));

        // 4b. The position is the AUTHORITY from T5b on, and this is where its refusal becomes the documented
        //     socket of the sale contract instead of a 500. V61's I8 refuses an outbound movement the ledger's
        //     position cannot cover by RAISEing inside the projection trigger, which reaches the caller as an
        //     UncategorizedSQLException — measured, and the shape this gate exists to replace. Reading the
        //     projection here gives the same refusal a shape the API documents: InsufficientStockException, 409,
        //     same message, and the number the position states.
        //
        // T5c: AND THE READ TAKES THE ROW'S LOCK. Until this slice the only reason two concurrent deductions
        // could not both pass this gate was the legacy store-stock row locked in step 3 — a lock T5b's readers
        // already outgrew and that the deferred T5d would remove, which would have left this gate deciding on a
        // number another transaction is free to change. A locking read serializes on the row the decision is
        // about, so a second deduction waits here, re-reads under READ COMMITTED, and refuses with the quantity
        // the first one committed. The store-stock lock is still taken first (step 3), so the documented lock
        // order is now storeStock -> stockPosition -> locationBalance and it is cycle-free: no path anywhere
        // takes stockPosition before the store row of the same key.
        // An absent row means the ledger never moved this key, and no lock can be taken on a row that does not
        // exist. That is fail-closed on purpose: the empty read is used as zero below, so a key with no position
        // can never authorize a sale — the same refusal the legacy aggregate produces for a variant this store
        // does not stock, and the only defensible reading of "the ledger holds nothing".
        // I8 stays the ledger's OWN gate and its OWN lock, and neither is redundant: V61's trigger takes
        // stock_position FOR UPDATE again (V61:365-369) for every writer that reaches the table — including one
        // that never walks this method — and refuses the movement if the row it locks cannot cover it. Two locks
        // on one row inside one transaction are one lock on the database; what is not duplicated is the audience.
        // This one turns the refusal into the API's 409 before any write happens; that one holds for every
        // caller, in the database, whether or not this slice's gate ran.
        var position = orZero(stockPositionRepository
                .findByCompanyStoreIdAndProductVariantIdForUpdate(companyStoreId, productVariantId)
                .map(StockPosition::getQuantity)
                .orElse(null));
        if (position.compareTo(quantity) < 0) {
            throw new InsufficientStockException(productVariantId, quantity, position);
        }

        // 5. The priority location comes from the store's settings. A missing row is a FIFO
        //    fallback with one warning, never a failure (W3-D8).
        UUID priorityLocationId = resolveSalesLocationId(companyStoreId);

        // 6. How much is where: the ledger's projection per location, in the order the legacy rows state.
        //    THE SOURCE OF THIS NUMBER MOVED, and that is the whole point of the slice: it used to be
        //    `product_variant_locations.stock`, the column the write path stops maintaining (T3) and the
        //    migration after that drops. A split read from a column nothing updates is a split read from a
        //    frozen number, and the case that pins this corrupts the column on purpose and asserts the sale
        //    still follows the projection.
        var storedByLocation = new LinkedHashMap<UUID, BigDecimal>();
        for (var balance : stockLedgerWriter.openBalances(companyStoreId, productVariantId)) {
            storedByLocation.put(balance.storeLocationId(), orZero(balance.quantity()));
        }

        // 7. Lock each balance row before writing it, in that same order so two movers take the same rows in
        //    the same sequence. storeStock is already held, so the documented order storeStock -> locationBalance
        //    holds.
        var lockedBalances = new LinkedHashMap<UUID, ProductVariantLocation>();
        for (var locationId : storedByLocation.keySet()) {
            lockedBalances.put(locationId, lockBalance(productVariantId, locationId));
        }

        // 8. Second fail-closed check (W3-D1), computed before any write: a store the position promised but the
        //    locations cannot cover is left untouched instead of partially deducted.
        var locationTotal = storedByLocation.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (locationTotal.compareTo(quantity) < 0) {
            throw new InsufficientStockException(productVariantId, quantity, locationTotal);
        }

        // 9. Consume: priority location first, then the FIFO order. One SALE movement per location actually
        //    consumed; a location holding nothing contributes no row.
        var remaining = quantity;
        for (var locationId : orderForDeduction(storedByLocation.keySet(), priorityLocationId)) {
            if (remaining.signum() == 0) {
                break;
            }
            var stock = storedByLocation.getOrDefault(locationId, BigDecimal.ZERO);
            var taken = stock.min(remaining);
            if (taken.signum() <= 0) {
                continue;
            }
            // 9a. One movement per location, and nothing else: the projection trigger is what moves the
            //     balances from here (S4). The legacy row that used to sit beside it is gone with the columns.
            stockLedgerWriter.bookSale(
                    booking(
                            referenceType,
                            companyId,
                            companyStoreId,
                            locationId,
                            productVariantId,
                            createdBy,
                            referenceId,
                            writeSeq),
                    taken);
            remaining = remaining.subtract(taken);
        }
    }

    /**
     * Reverses a sale by its ledger reference: for every location that gave stock to
     * {@code (referenceType, referenceId)}, credits back exactly what it gave and appends a
     * {@code SALE_REVERSAL} movement for the uncovered remainder only.
     *
     * <p>The reversal never re-runs the priority allocation (W3-D6). Re-allocating would restore a
     * spilled sale into the priority location and drift the distribution permanently while the
     * aggregate stayed correct. The remainder is {@code SUM(SALE) - SUM(SALE_REVERSAL)} per
     * reference and location, so a second reversal finds nothing uncovered and is a no-op: the
     * operation is idempotent by construction.</p>
     *
     * <p>Because the locations come from the ledger, the store's settings row is irrelevant here and
     * a store without one reverses normally (W3-D8), with no warning. Lock order is the deduction's:
     * the per-store stock row first, then the balance rows, locked in {@code store_location_id}
     * order.</p>
     *
     * @param referenceType source discriminator of the sale, as written by the deduction
     * @param referenceId identity of that source
     * @param createdBy username of the actor, when known
     * @throws IllegalArgumentException when the reference is missing, or when a store the ledger
     *     points at has no stock row
     */
    public void applySaleReversal(String referenceType, UUID referenceId, String createdBy) {

        if (referenceType == null || referenceType.isBlank() || referenceId == null) {
            throw new IllegalArgumentException("A sale reversal needs the reference of the sale it reverses, but got"
                    + " referenceType=" + referenceType + ", referenceId=" + referenceId);
        }

        // 1. The ledger, not the legacy table, says what each location gave: the rows still open are the
        //    remainder. There is no subtraction to do and nothing to compare it against — the set of open rows
        //    IS the amount a reversal credits, which is why no remainder arithmetic survives this slice
        //    (odd/tasks/ledger-mirror-retirement.md, S3).
        var openSales = openLedgerSales(referenceType, referenceId);
        if (openSales.isEmpty()) {
            // Nothing was sold under this reference, or everything is already reversed: no-op.
            return;
        }
        var uncovered = new LinkedHashMap<SaleBalanceKey, BigDecimal>();
        openSales.forEach((key, sales) -> {
            var total = BigDecimal.ZERO;
            for (var sale : sales) {
                total = total.add(sale.quantity());
            }
            uncovered.put(key, total);
        });

        // 2. Group per (variant, store) in a deterministic order, then reverse each scope with the
        //    documented lock order storeStock -> locationBalance.
        var keys = new ArrayList<>(uncovered.keySet());
        keys.sort(Comparator.comparing(SaleBalanceKey::productVariantId)
                .thenComparing(SaleBalanceKey::companyStoreId)
                .thenComparing(SaleBalanceKey::storeLocationId));
        var index = 0;
        while (index < keys.size()) {
            var scope = keys.get(index);
            var locationAmounts = new TreeMap<UUID, BigDecimal>();
            while (index < keys.size()
                    && keys.get(index).productVariantId().equals(scope.productVariantId())
                    && keys.get(index).companyStoreId().equals(scope.companyStoreId())) {
                var key = keys.get(index);
                locationAmounts.put(key.storeLocationId(), uncovered.get(key));
                index++;
            }
            reverseScope(
                    scope.productVariantId(),
                    scope.companyStoreId(),
                    locationAmounts,
                    openSales,
                    referenceType,
                    referenceId,
                    createdBy);
        }
    }

    /**
     * One piece of sale work of a batch, keyed by the {@code (store, variant)} row the batch locks
     * first. A {@code reversal} gives the reference back; otherwise the change sells
     * {@code quantity} under it. The engine derives the locations from the store's settings or from
     * the ledger, so the key exists only for ordering and locking.
     *
     * <p><b>{@code writeSeq} is the sale line's own counter</b> ({@code sales_order_items.write_seq},
     * {@code V73}): which write of that line the movement belongs to. It travels as a component of the change so
     * the batch path threads it per line, and the seven-argument {@link #applySaleDeduction} states it directly for
     * a caller that books one reference; the six-argument shape states {@link StockLedgerWriter#SINGLE_WRITE}. No
     * shape of this engine invents a number: the counter belongs to the document, and a caller that books a line
     * twice has to say which write each movement is. A reversal states {@code SINGLE_WRITE} because a reversal row
     * is outside {@code uq_movement_source} and has no counter of its own.</p>
     */
    public record SaleChange(
            UUID companyStoreId,
            UUID productVariantId,
            BigDecimal quantity,
            UUID referenceId,
            boolean reversal,
            long writeSeq) {

        /**
         * Sells {@code quantity} of the variant in the store, under the line-level reference the ledger carries
         * (W3-D6), as the line's {@code writeSeq}-th write.
         */
        public static SaleChange deduction(
                UUID companyStoreId, UUID productVariantId, BigDecimal quantity, UUID referenceId, long writeSeq) {
            return new SaleChange(companyStoreId, productVariantId, quantity, referenceId, false, writeSeq);
        }

        /**
         * The same deduction for a caller that owns no counter: it books as the reference's only write
         * ({@link StockLedgerWriter#SINGLE_WRITE}). A document line that can book more than once states its own
         * sequence through {@link #deduction(UUID, UUID, BigDecimal, UUID, long)} — this shape exists for the
         * engine-level callers and fixtures that sell a reference once, and it says so instead of leaving the
         * number to a default.
         */
        public static SaleChange deduction(
                UUID companyStoreId, UUID productVariantId, BigDecimal quantity, UUID referenceId) {
            return deduction(companyStoreId, productVariantId, quantity, referenceId, StockLedgerWriter.SINGLE_WRITE);
        }

        /**
         * Gives back whatever the reference still holds, so the same line id says which sale to
         * balance; {@code quantity} plays no part and is null.
         */
        public static SaleChange reversal(UUID companyStoreId, UUID productVariantId, UUID referenceId) {
            return new SaleChange(
                    companyStoreId, productVariantId, null, referenceId, true, StockLedgerWriter.SINGLE_WRITE);
        }
    }

    /** Composite identity of a per-store stock row: the batch's sort key and its first lock. */
    private record StoreVariantKey(UUID companyStoreId, UUID productVariantId) implements Comparable<StoreVariantKey> {

        @Override
        public int compareTo(StoreVariantKey other) {
            var storeOrder = companyStoreId.compareTo(other.companyStoreId);
            return storeOrder != 0 ? storeOrder : productVariantId.compareTo(other.productVariantId);
        }
    }

    /**
     * Applies one batch of sale work, owning the two ordering rules instead of lending them to the
     * caller (F-1 of {@code odd/tasks/sales-location-aware-stock.md}).
     *
     * <p>First every distinct {@code (store, variant)} row is locked in {@link StoreVariantKey}
     * order, so the acquisition order is ascending and deadlock-free even though the work runs in a
     * different order. Then every reversal runs before every deduction. The engine takes the
     * per-store row as its FIRST lock and holds it to the commit, so the documented
     * {@code storeStock -> locationBalance} order is untouched, and re-locking a held row inside the
     * same transaction is a no-op. A row that does not exist is simply not locked: the work that
     * follows raises its own exception.</p>
     *
     * <p>Reversals must precede deductions because a reversal is reference-scoped: it credits
     * whatever the line's ledger reference still holds, so a deduction of the same reference in the
     * same batch would put a {@code SALE} row in front of it and the reversal would credit the fresh
     * sale back. A variant change registers the reversal of the old variant and the deduction of the
     * new one under one line reference and two different sort keys, so a batch run in its input
     * order would let the deduction win. That is why the caller's order is irrelevant here:
     * {@code changes} may name the deduction of a reference before its reversal.</p>
     *
     * @param referenceType source discriminator carrying the line-level provenance of every sale in
     *     the batch (W3-D6)
     * @param createdBy username of the actor, when known
     * @param changes the batch, in any order; an empty batch locks nothing and writes no movement
     * @throws IllegalArgumentException as {@link #applySaleDeduction} and {@link #applySaleReversal}
     * @throws InsufficientStockException as {@link #applySaleDeduction}
     * @throws ProductVariantNotFoundException as {@link #applySaleDeduction}
     */
    public void applySaleBatch(String referenceType, String createdBy, List<SaleChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        var ordered = changes.stream()
                .sorted(Comparator.comparing(InventoryService::keyOf))
                .toList();

        // 1. Lock every distinct row first, in key order. The phases below invert the key order
        //    whenever a reversal sorts after a deduction, so the locks cannot be left to the work.
        ordered.stream()
                .map(InventoryService::keyOf)
                .distinct()
                .forEach(key -> lockStoreStock(key.productVariantId(), key.companyStoreId()));

        // 2. Every reversal before every deduction.
        ordered.stream()
                .filter(SaleChange::reversal)
                .forEach(change -> applySaleReversal(referenceType, change.referenceId(), createdBy));
        ordered.stream()
                .filter(change -> !change.reversal())
                .forEach(change -> applySaleDeduction(
                        change.productVariantId(),
                        change.companyStoreId(),
                        change.quantity(),
                        referenceType,
                        change.referenceId(),
                        createdBy,
                        change.writeSeq()));
    }

    private static StoreVariantKey keyOf(SaleChange change) {
        return new StoreVariantKey(change.companyStoreId(), change.productVariantId());
    }

    /**
     * Sets the per-store sellable stock to {@code newStock} and moves every side of the invariant
     * with it: the aggregate, the store's sales-location balance and one ledger row explaining the
     * delta (W3-D14).
     *
     * <p>This is the write behind the per-store stock editor
     * ({@code ProductVariantService.upsertStoreStock}). The editor sends an ABSOLUTE value, so the
     * engine derives the delta itself — {@code newStock - current aggregate} — and moves the
     * locations by that delta. An increase credits the store's configured {@code sales_location_id}:
     * there is nothing to draw from, and the operator is asserting a total rather than naming a
     * shelf. A decrease is ALLOCATED over the store's locations exactly as a sale is (W3-D1) — the
     * sales location first, then the remaining balances in FIFO order — so no location is driven
     * below zero, and one ledger row explains each location it draws from. The aggregate is never
     * set without the locations moving by an identical amount, which keeps
     * {@code aggregate = SUM(locations)} true by construction instead of by convention.</p>
     *
     * <p>A store with no {@code store_inventory_settings} row is REFUSED with
     * {@link StoreInventorySettingsNotFoundException} rather than guessed. F16 measured the exact
     * divergence this method exists to stop — aggregate 11.00 against one location of 1.00, produced
     * by this editor and nothing else — and a guessed destination would reproduce it. This is the
     * opposite of the sale deduction's FIFO fallback (W3-D8): a missing configuration row must not
     * turn a sale into an outage, but a hand edit is an explicit act of placement and may require the
     * store to be configured first.</p>
     *
     * <p>The type carries the direction (W3-D7): an increase writes
     * {@link MovementType#ADJUSTMENT_INCREASE} and a decrease {@link MovementType#ADJUSTMENT_DECREASE},
     * both with a positive quantity. A decrease that spans several locations writes one
     * {@code ADJUSTMENT_DECREASE} row per location drawn from, exactly as a sale writes one
     * {@code SALE} row per location consumed, so the ledger answers where the number went. The
     * ledger reference is the editor itself: {@code referenceType} is
     * {@value #MANUAL_STOCK_EDIT_REFERENCE_TYPE} and {@code referenceId} is null, because there is no
     * source document to point at.</p>
     *
     * <p>Lock order is unchanged: {@code storeStock -> locationBalance}. Resolving the destination
     * from the settings is a plain read, not a lock, and a decrease locks every balance row of the
     * variant in the store in the same FIFO order the deduction uses. A target equal to the current
     * aggregate is a no-op on the balance rows and writes no movement, while the settings refusal
     * still applies, so the precondition does not depend on the delta.</p>
     *
     * @param newStock absolute sellable stock to set; must not be negative
     * @param createdBy username of the operator who made the edit, when known
     * @return the resulting aggregate stock, which is {@code newStock}
     * @throws IllegalArgumentException when {@code newStock} is null or negative, or when the variant
     *     has no stock row in {@code companyStoreId}
     * @throws StoreInventorySettingsNotFoundException when the store has no settings row, so no
     *     destination location exists
     * @throws InsufficientStockException when the locations cannot cover a decrease; unreachable
     *     through the application while {@code aggregate = SUM(locations)} holds
     */
    public BigDecimal applyStockAdjustment(
            UUID productVariantId, UUID companyStoreId, BigDecimal newStock, String createdBy) {

        // 1. Reject an invalid target before touching the database.
        if (newStock == null || newStock.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "Manually set stock must be greater than or equal to zero, but was " + newStock);
        }

        // 2. The destination comes from the store's settings, and its absence is a refusal (W3-D14):
        //    guessing where the goods sit is how the aggregate and the locations drift apart (F16).
        //    Contrast W3-D8, where the same absence is a FIFO fallback because a sale cannot fail on
        //    a missing configuration row.
        var settings = storeInventorySettingsRepository
                .findByCompanyStoreId(companyStoreId)
                .orElseThrow(() -> new StoreInventorySettingsNotFoundException(companyStoreId));
        var destinationLocationId = settings.getSalesLocationId();

        // 3. FIRST lock: the per-store stock row, the serialization point of every stock mover. The
        //    editor creates the row before delegating, so its absence here is a caller error.
        var storeStock = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreIdForUpdate(productVariantId, companyStoreId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product variant " + productVariantId + " has no stock row in store " + companyStoreId));

        // 4. The delta is the whole point and it is computed HERE, never in the product service, so a single
        //    owner holds the arithmetic. The current number comes from the store's POSITION — the projection —
        //    and not from the legacy store column: that column stopped being maintained when the other writers
        //    stopped (T3c), so reading it here would compute the delta against a frozen value and set the target
        //    to the wrong number while every projection stayed self-consistent. The read takes the row's lock for
        //    the same reason the sale's gate does: the number the delta is taken from must not move under it.
        var currentStock = orZero(stockPositionRepository
                .findByCompanyStoreIdAndProductVariantIdForUpdate(companyStoreId, productVariantId)
                .map(StockPosition::getQuantity)
                .orElse(null));
        var delta = newStock.subtract(currentStock);

        // 5. Move the locations by the same delta. A zero delta moves nothing and writes nothing:
        //    the ledger records facts, not no-ops. An increase credits the destination (nothing to
        //    draw from); a decrease is ALLOCATED like a sale (W3-D1), so it cannot be assigned to one
        //    location and drive it below zero while aggregate = SUM(locations) still holds (F18).
        if (delta.signum() > 0) {
            productVariantLocationRepository.insertBalanceIfAbsent(productVariantId, destinationLocationId);
            // Locked for the same reason the store row is: the number the delta was taken from must not move
            // under the edit. The value itself is the projection's now, so nothing reads it back here.
            lockBalance(productVariantId, destinationLocationId);

            // 5b. One ADJUSTMENT_IN row, and it states its own identity: a manual edit has no document to
            //     name, so its source is the movement's own id (D-2), and the id drawn here becomes the row's
            //     id. That is not shape (b) of the ledger's identity — nothing is retried by a manual edit's
            //     identity, so the key refuses nothing for it and needs to refuse nothing. ADJUSTMENT_IN adds
            //     quantity, so it states a cost and a currency (ck_movement_inbound_cost), and the operator's
            //     cost is the one this store records.
            bookLedgerInbound(
                    StockLedgerWriter.MANUAL_ADJUSTMENT_SOURCE_TYPE,
                    companyIdOf(companyStoreId),
                    companyStoreId,
                    destinationLocationId,
                    productVariantId,
                    delta,
                    storeStock.getCostPrice(),
                    UUID.randomUUID(),
                    createdBy,
                    false);
        } else if (delta.signum() < 0) {
            applyAdjustmentDecrease(productVariantId, companyStoreId, destinationLocationId, delta.negate(), createdBy);
        }

        // 6. The aggregate takes the absolute target, never a re-derivation from the location sum:
        //    the recompute question is closed (W3-D5), and the locations already moved by the delta.
        return newStock;
    }

    /**
     * Allocates a manual decrease over the store's locations instead of assigning it to one: the
     * store's sales location first, then the remaining balances in the repository's FIFO order — the
     * same order and the same {@link #orderForDeduction} helper a sale uses (W3-D1), because a
     * second allocation rule for one concept is how two owners of the same number start. One
     * {@link MovementType#ADJUSTMENT_DECREASE} row is written per location actually drawn from, so
     * the ledger explains every balance the edit moved.
     *
     * <p>Failure is closed as the sale's second check is: the locked balances must together cover the
     * decrease. Once {@code aggregate = SUM(locations)} holds (after the W3-D15 reset and by the four
     * writers), the guard is unreachable through the application — the target is non-negative, so the
     * decrease can never exceed the aggregate, which equals the location sum — and it exists for the
     * transient state a writer outside the invariant would produce. The rows are locked in FIFO order
     * after the per-store stock row, preserving the documented {@code storeStock -> locationBalance}
     * order.</p>
     */
    private void applyAdjustmentDecrease(
            UUID productVariantId, UUID companyStoreId, UUID priorityLocationId, BigDecimal amount, String createdBy) {

        // Same source as a sale's split (T3): the ledger's projection, never the column.
        var storedByLocation = new LinkedHashMap<UUID, BigDecimal>();
        for (var balance : stockLedgerWriter.openBalances(companyStoreId, productVariantId)) {
            storedByLocation.put(balance.storeLocationId(), orZero(balance.quantity()));
        }
        var lockedBalances = new LinkedHashMap<UUID, ProductVariantLocation>();
        for (var locationId : storedByLocation.keySet()) {
            lockedBalances.put(locationId, lockBalance(productVariantId, locationId));
        }

        var locationTotal = storedByLocation.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (locationTotal.compareTo(amount) < 0) {
            throw new InsufficientStockException(productVariantId, amount, locationTotal);
        }

        var remaining = amount;
        var companyId = companyIdOf(companyStoreId);
        for (var locationId : orderForDeduction(storedByLocation.keySet(), priorityLocationId)) {
            if (remaining.signum() == 0) {
                break;
            }
            var stock = storedByLocation.getOrDefault(locationId, BigDecimal.ZERO);
            var taken = stock.min(remaining);
            if (taken.signum() <= 0) {
                continue;
            }
            // One ADJUSTMENT_OUT per location drawn from, each with its allocations: an outbound row that is
            // not allocated violates I6, and the allocation writer is the only consumption path (decision 3).
            // Its source is its own id, by the same reason the increase states (D-2).
            stockLedgerWriter.bookAdjustmentOut(
                    booking(
                            StockLedgerWriter.MANUAL_ADJUSTMENT_SOURCE_TYPE,
                            companyId,
                            companyStoreId,
                            locationId,
                            productVariantId,
                            createdBy,
                            UUID.randomUUID(),
                            StockLedgerWriter.SINGLE_WRITE),
                    taken);
            remaining = remaining.subtract(taken);
        }
    }

    /**
     * Takes the pessimistic lock on one {@code (variant, store)} stock row without moving it, so
     * {@link #applySaleBatch} can take every row of its batch in a deterministic order before it
     * runs any of the work. It is the same first lock every mover takes, which preserves the
     * documented {@code storeStock -> locationBalance} order, and re-locking an already-held row
     * inside the same transaction is a no-op. A row that does not exist is simply not locked: the
     * operation that follows raises its own exception.
     */
    private void lockStoreStock(UUID productVariantId, UUID companyStoreId) {
        productVariantStoreStockRepository.findByProductVariantIdAndCompanyStoreIdForUpdate(
                productVariantId, companyStoreId);
    }

    /**
     * Credits one {@code (variant, store)} scope back to the locations of its remainder and to the
     * store's aggregate. The balance rows are locked in {@code store_location_id} order, after the
     * per-store stock row.
     *
     * <p>Each location's ledger reversal is written after the legacy row it mirrors, and there can be more
     * ledger reversals than legacy rows at one location: the legacy engine writes <b>one</b>
     * {@code SALE_REVERSAL} per location for the whole remaining amount, while the ledger has to name every
     * {@code SALE} row it gives back, and a line that was sold twice — a grown quantity sells the difference
     * under the same reference — has two open rows there. That is legal in the ledger by construction:
     * {@code uq_movement_source} is partial on {@code reverses_movement_id IS NULL}, so a reversal row is not
     * in it, and {@code uq_movement_one_reversal} is what bounds the set instead — at most one reversal per
     * reversed row.</p>
     *
     * <p>Each reversal states the caller's source and moves exactly the quantity of the row it names
     * ({@code V61}), and {@code uq_movement_one_reversal} bounds the set: at most one reversal per movement. The
     * "partial remainder" this method used to refuse cannot arise any more, and that is a consequence worth
     * naming: the amount to credit is now the sum of the open rows themselves, so there is no second arithmetic
     * that could disagree with them.</p>
     */
    private void reverseScope(
            UUID productVariantId,
            UUID companyStoreId,
            NavigableMap<UUID, BigDecimal> locationAmounts,
            Map<SaleBalanceKey, List<OpenSale>> openSales,
            String referenceType,
            UUID referenceId,
            String createdBy) {

        // The store row is still locked first (the order every mover agrees on) and its number is no longer
        // read: the reversal states what the ledger holds open, and the projections follow by trigger.
        productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreIdForUpdate(productVariantId, companyStoreId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product variant " + productVariantId + " has no stock row in store " + companyStoreId));

        var companyId = companyIdOf(companyStoreId);
        for (var entry : locationAmounts.entrySet()) {
            lockBalance(productVariantId, entry.getKey());
            var open = openSales.getOrDefault(
                    new SaleBalanceKey(productVariantId, companyStoreId, entry.getKey()), List.of());
            for (var sale : open) {
                // The reversal names the SALE LINE, not the mirrored row it used to hang off: its own source
                // travels from the caller, and reverses_movement_id is what says which movement it corrects.
                // A reversal row is outside uq_movement_source (the predicate is reverses_movement_id IS NULL),
                // so it needs no write sequence of its own.
                stockLedgerWriter.bookSaleReversal(
                        booking(
                                referenceType,
                                companyId,
                                companyStoreId,
                                entry.getKey(),
                                productVariantId,
                                createdBy,
                                referenceId,
                                StockLedgerWriter.SINGLE_WRITE),
                        sale.quantity(),
                        sale.ledgerMovementId());
            }
        }
    }

    /**
     * The store's configured sales location, or {@code null} when the store has no settings row. A
     * missing row is a FIFO fallback with exactly one warning, never a failure (W3-D8).
     */
    private UUID resolveSalesLocationId(UUID companyStoreId) {
        var settings = storeInventorySettingsRepository.findByCompanyStoreId(companyStoreId);
        if (settings.isEmpty()) {
            logger.warn(
                    "Store {} has no store_inventory_settings row; allocating sale stock FIFO across its locations",
                    companyStoreId);
            return null;
        }
        return settings.get().getSalesLocationId();
    }

    /**
     * The consumption order of the locked balances: the store's priority location first, then the
     * order the repository returned (FIFO). A priority location without a balance row of the variant
     * is simply absent from the list.
     */
    private static List<UUID> orderForDeduction(Collection<UUID> locationIds, UUID priorityLocationId) {
        var ordered = new ArrayList<UUID>(locationIds.size());
        if (priorityLocationId != null && locationIds.contains(priorityLocationId)) {
            ordered.add(priorityLocationId);
        }
        locationIds.stream().filter(locationId -> !ordered.contains(locationId)).forEach(ordered::add);
        return ordered;
    }

    /** The ledger identity a reversal balances per location: one row per variant, store and location. */
    private record SaleBalanceKey(UUID productVariantId, UUID companyStoreId, UUID storeLocationId) {}

    /**
     * One remainder the ledger still holds open: a {@code SALE} row no reversal names yet, and the quantity
     * it moved. It is what a reversal has to name to free exactly the rows the sale consumed.
     */
    private record OpenSale(UUID ledgerMovementId, BigDecimal quantity) {}

    /**
     * The key every ledger row needs, plus the identity of the row that states the fact.
     *
     * <p>{@code sourceType} and {@code writeSeq} are both required, and every call site in this class states
     * them, because nothing is implied by the writer and the identity of every movement has to be readable where
     * the fact is booked. Since T3c the sources are all the document's own: a receipt line states
     * {@link StockLedgerWriter#RECEIPT_LINE_SOURCE_TYPE} and the {@code goods_receipt_items} id, a sale and its
     * reversal state the sales-order line and the line's {@code writeSeq} ({@code V73}), and a manual per-store
     * edit states {@link StockLedgerWriter#MANUAL_ADJUSTMENT_SOURCE_TYPE} and the movement's own id, because no
     * document states a manual edit (D-2). {@link StockLedgerWriter#MIRRORED_SOURCE_TYPE} is no longer written by
     * anything in {@code src/main}: it survives only as what the pre-{@code V73} fallback reads, and it goes with
     * the table (T4).</p>
     */
    private static StockLedgerWriter.Booking booking(
            String sourceType,
            UUID companyId,
            UUID companyStoreId,
            UUID storeLocationId,
            UUID productVariantId,
            String createdBy,
            UUID sourceId,
            long writeSeq) {
        return new StockLedgerWriter.Booking(
                companyId,
                companyStoreId,
                storeLocationId,
                productVariantId,
                createdBy,
                sourceType,
                sourceId,
                writeSeq);
    }

    /**
     * Books an inbound movement (a receipt or the increase side of a manual edit) in the ledger, which is the only
     * write: the projections follow from it by trigger and the legacy columns are no longer maintained (S4).
     *
     * <p>The cost is what {@code product_variant_store_stock.cost_price} records for this variant in this
     * store — the only cost this deployment keeps, and the same source {@code V62}'s backfill used. A missing
     * value is booked as zero with the reason stated on the row, never refused: a migration that cannot run
     * gets edited under pressure, and a receipt of stock that has no recorded cost is still a receipt.</p>
     *
     * @param receipt whether the row is a {@code RECEIPT} rather than an {@code ADJUSTMENT_IN}; the two differ
     *     only in the movement type and in what explains them
     */
    private void bookLedgerInbound(
            String sourceType,
            UUID companyId,
            UUID companyStoreId,
            UUID storeLocationId,
            UUID productVariantId,
            BigDecimal quantity,
            BigDecimal recordedCost,
            UUID sourceId,
            String createdBy,
            boolean receipt) {
        var booking = booking(
                sourceType,
                companyId,
                companyStoreId,
                storeLocationId,
                productVariantId,
                createdBy,
                sourceId,
                StockLedgerWriter.SINGLE_WRITE);
        var unitCost = orZero(recordedCost);
        var currency = stockLedgerWriter.operatingCurrency(companyId);
        var reason = recordedCost == null ? UNRECORDED_COST_REASON : null;
        if (receipt) {
            stockLedgerWriter.bookReceipt(booking, quantity, unitCost, currency, reason);
        } else {
            stockLedgerWriter.bookAdjustmentIn(booking, quantity, unitCost, currency, reason);
        }
    }

    /**
     * The sale movements of one reference that the ledger still holds open, grouped by the key a reversal
     * balances, oldest first.
     *
     * <p><b>Read by the source the movements carry, which is the point of the per-write identity.</b> Since
     * {@code V73} a sale books {@code source_type}/{@code source_id} = the line's own reference and the line id,
     * so the ledger alone answers "what does this line still hold open": one row per write and location, each
     * with the quantity it moved. That is a strictly larger set than the old answer could express — a line sold,
     * grown and sold again holds two open rows at one location — and it is the set a reversal has to name, because
     * {@code V61} demands a reversal move exactly the quantity of the row it names.</p>
     *
     * <p><b>Rows booked before this identity existed are still reachable, and both identities are merged</b>: a
     * sale written before the deploy carries {@code INVENTORY_MOVEMENT} and the id of the legacy row beside it, so
     * {@link #openLedgerSalesBookedOnTheMirror} answers for those by the mirror. The two reads are unioned by
     * ledger movement id rather than one replacing the other, because a reference whose stock spans the deploy
     * holds rows of both shapes and a reversal has to give back every open row it can name — refusing that
     * reference, which is what the legacy remainder arithmetic used to do, was an artefact of deriving the amount
     * from a second table. The mirror half dies with the table (T4); this method, its name and its caller do not.
     * </p>
     */
    private Map<SaleBalanceKey, List<OpenSale>> openLedgerSales(String referenceType, UUID referenceId) {
        var open = new LinkedHashMap<SaleBalanceKey, List<OpenSale>>();
        for (var sale : stockLedgerWriter.openSales(referenceType, referenceId)) {
            open.computeIfAbsent(
                            new SaleBalanceKey(sale.productVariantId(), sale.companyStoreId(), sale.storeLocationId()),
                            key -> new ArrayList<>())
                    .add(new OpenSale(sale.movementId(), orZero(sale.quantity())));
        }
        return mergeOpenSales(open, openLedgerSalesBookedOnTheMirror(referenceType, referenceId));
    }

    /** Both identities of one reference, by key and deduplicated by the ledger row each entry names. */
    private static Map<SaleBalanceKey, List<OpenSale>> mergeOpenSales(
            Map<SaleBalanceKey, List<OpenSale>> first, Map<SaleBalanceKey, List<OpenSale>> second) {
        second.forEach((key, sales) -> {
            var known = first.computeIfAbsent(key, ignored -> new ArrayList<>());
            for (var sale : sales) {
                var alreadyThere = known.stream()
                        .anyMatch(entry -> entry.ledgerMovementId().equals(sale.ledgerMovementId()));
                if (!alreadyThere) {
                    known.add(sale);
                }
            }
        });
        return first;
    }

    /**
     * The same question for a ledger whose sale rows predate {@code V73}: each is found by the legacy row it
     * mirrors, which is what {@code source_type = 'INVENTORY_MOVEMENT'} and a primary-key {@code source_id} can
     * still answer.
     *
     * <p>Read from the legacy rows the reversal already reads, each resolved to the ledger row that mirrors it.
     * A legacy sale that has no ledger twin predates this slice's writers (the deploy window), and it is refused
     * rather than guessed at: the ledger holds no movement to reverse, and inventing one would record a fact nobody
     * observed.</p>
     */
    private Map<SaleBalanceKey, List<OpenSale>> openLedgerSalesBookedOnTheMirror(
            String referenceType, UUID referenceId) {
        var sales = new ArrayList<InventoryMovement>();
        for (var movement : inventoryMovementRepository.findByReferenceTypeAndReferenceId(referenceType, referenceId)) {
            if (movement.getMovementType() == MovementType.SALE) {
                sales.add(movement);
            }
        }
        sales.sort(
                Comparator.comparing(InventoryMovement::getOccurredAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(InventoryMovement::getId, Comparator.nullsFirst(Comparator.naturalOrder())));

        var open = new LinkedHashMap<SaleBalanceKey, List<OpenSale>>();
        for (var sale : sales) {
            var mirrored = stockLedgerWriter.mirroredMovementId(sale.getId(), StockLedgerWriter.SALE);
            if (mirrored.isEmpty()) {
                // No mirror twin. Since T2 that is the NORMAL case for a sale booked by the current writer: its
                // ledger row names the line, and the primary read above already carries it. A row that is neither
                // mirrored nor line-keyed is a deploy-window sale, and the migration that drops the mirror refuses
                // while one is still unreversed (odd/tasks/ledger-mirror-retirement.md, D-1) rather than this read
                // guessing at it.
                continue;
            }
            var ledgerMovementId = mirrored.get();
            if (stockLedgerWriter.isReversed(ledgerMovementId)) {
                continue;
            }
            open.computeIfAbsent(
                            new SaleBalanceKey(
                                    sale.getProductVariantId(), sale.getCompanyStoreId(), sale.getStoreLocationId()),
                            key -> new ArrayList<>())
                    .add(new OpenSale(ledgerMovementId, orZero(sale.getQuantity())));
        }
        return open;
    }

    /** NULL stock only means "never set" — a row read back before its default is materialized. */
    private static BigDecimal orZero(BigDecimal stock) {
        return stock != null ? stock : BigDecimal.ZERO;
    }

    /**
     * The company that owns a store, read from the store itself.
     *
     * <p>This service deliberately holds no {@code CurrentUserContext}: a stock movement is the
     * document's business, and the document already verified the caller's grant on its store. What the
     * argument does carry is the tenant, and this is the one place it is derived from
     * ({@link CompanyStoreOwnerResolver}) so that a movement and every store-scoped read agree on which
     * company a store belongs to. The extra read is the price of asking the question before the first
     * lock, which is where it has to be asked.</p>
     *
     * @throws CompanyStoreNotFoundException when the store does not exist
     */
    private UUID companyIdOf(UUID companyStoreId) {
        var store = companyStoreRepository
                .findById(companyStoreId)
                .orElseThrow(() -> new CompanyStoreNotFoundException(companyStoreId));
        return companyStoreOwnerResolver.companyIdOf(store);
    }

    private ProductVariantLocation lockBalance(UUID productVariantId, UUID storeLocationId) {
        return productVariantLocationRepository
                .findByProductVariantIdAndStoreLocationIdForUpdate(productVariantId, storeLocationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Product variant location balance not found for variant " + productVariantId
                                + " and store location " + storeLocationId + " after it was locked or created"));
    }
}
