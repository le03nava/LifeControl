package com.lifecontrol.api.inventory.service;

import com.lifecontrol.api.inventory.exception.StoreInventorySettingsNotFoundException;
import com.lifecontrol.api.inventory.ledger.StockLedgerWriter;
import com.lifecontrol.api.inventory.model.InventoryMovement;
import com.lifecontrol.api.inventory.model.MovementType;
import com.lifecontrol.api.inventory.model.ProductVariantLocation;
import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import com.lifecontrol.api.inventory.repository.ProductVariantLocationRepository;
import com.lifecontrol.api.inventory.repository.StoreInventorySettingsRepository;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import com.lifecontrol.api.salesorder.exception.InsufficientStockException;
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

    public InventoryService(
            ProductVariantRepository productVariantRepository,
            ProductVariantStoreStockRepository productVariantStoreStockRepository,
            ProductVariantLocationRepository productVariantLocationRepository,
            InventoryMovementRepository inventoryMovementRepository,
            StoreInventorySettingsRepository storeInventorySettingsRepository,
            CompanyStoreRepository companyStoreRepository,
            CompanyStoreOwnerResolver companyStoreOwnerResolver,
            StockLedgerWriter stockLedgerWriter) {
        this.productVariantRepository = productVariantRepository;
        this.productVariantStoreStockRepository = productVariantStoreStockRepository;
        this.productVariantLocationRepository = productVariantLocationRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.storeInventorySettingsRepository = storeInventorySettingsRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.companyStoreOwnerResolver = companyStoreOwnerResolver;
        this.stockLedgerWriter = stockLedgerWriter;
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
        ProductVariantLocation location = lockBalance(productVariantId, storeLocationId);

        // 5. Append the ledger entry. The movement is the explanation of the change, so both
        //    balance mutations below always have their RECEIPT row.
        var movement = InventoryMovement.builder()
                .productVariantId(productVariantId)
                .companyStoreId(companyStoreId)
                .storeLocationId(storeLocationId)
                .movementType(MovementType.RECEIPT)
                .quantity(quantity)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .createdBy(createdBy)
                .build();
        inventoryMovementRepository.save(movement);

        // 5b. THE SAME FACT, IN THE LEDGER, BESIDE IT. One RECEIPT row that adds the quantity this line
        //     received, at the cost this store records for the variant and in the company's operating
        //     currency — ck_movement_inbound_cost (V60) requires both on every row that adds quantity.
        //     The projection trigger derives stock_balance, stock_position and stock_valuation from it,
        //     so from this row on the ledger's numbers are the legacy numbers.
        bookLedgerInbound(
                StockLedgerWriter.MIRRORED_SOURCE_TYPE,
                companyId,
                companyStoreId,
                storeLocationId,
                productVariantId,
                quantity,
                storeStock.getCostPrice(),
                movement.getId(),
                createdBy,
                true);

        // 6. ADDITIVE, NEVER A RECOMPUTE. The location balance and the store's sellable stock are
        //    both incremented here, by the same quantity, so this is correct by construction
        //    (W3-D5) and keeps aggregate = SUM(locations). Neither side may be re-derived from the
        //    other: deriving the sellable stock from the location sum is the one operation that
        //    would silently resurrect stock a sale already sold if a writer ever left the two sides
        //    disagreeing. The switch to recompute semantics is closed, not deferred.
        // A NULL stock means the column was never set (its default is 0), so it counts as zero.
        location.setStock(orZero(location.getStock()).add(quantity));
        productVariantLocationRepository.save(location);

        storeStock.setStock(orZero(storeStock.getStock()).add(quantity));
        productVariantStoreStockRepository.save(storeStock);
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
     * <p>Lock order is unchanged: the definition is read without a lock, then
     * {@code storeStock -> locationBalance}. The shared {@code storeStock} row is the first lock every
     * mover of this variant and store takes, so two movers are serialized before either of them reaches a
     * balance row, and the balance rows they can then contend for all belong to that same
     * {@code (variant, store)} pair. The order in which balances are taken therefore cannot invert between
     * two movers: this method walks them in FIFO order, the reversal walks them by
     * {@code store_location_id}, and neither order can meet the other.</p>
     *
     * @param quantity signed amount sold; must be greater than zero
     * @param referenceType source discriminator carrying the line-level provenance of the sale (W3-D6)
     * @param referenceId identity of that source
     * @param createdBy username of the seller, when known
     * @throws IllegalArgumentException when {@code quantity} is not greater than zero
     * @throws InsufficientStockException when the store cannot cover {@code quantity} — including
     *     when the store does not stock the variant at all, which is zero sellable stock — first
     *     against the aggregate and then against the sum of the store's location balances
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
            String createdBy) {

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
        var storeStock = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreIdForUpdate(productVariantId, companyStoreId)
                .orElseThrow(() -> new InsufficientStockException(productVariantId, quantity, BigDecimal.ZERO));

        // 4. First fail-closed check (W3-D1): the aggregate is the sellable number and the
        //    precondition of the sale.
        var available = orZero(storeStock.getStock());
        if (available.compareTo(quantity) < 0) {
            throw new InsufficientStockException(productVariantId, quantity, available);
        }

        // 5. The priority location comes from the store's settings. A missing row is a FIFO
        //    fallback with one warning, never a failure (W3-D8).
        UUID priorityLocationId = resolveSalesLocationId(companyStoreId);

        // 6. Every balance row of the variant in this store, in the repository's FIFO order.
        var storeBalances = productVariantLocationRepository.findByProductVariantIdAndCompanyStoreId(
                productVariantId, companyStoreId);

        // 7. Lock each balance before reading it, in the FIFO order of the query so two movers take
        //    the same rows in the same sequence. storeStock is already held, so the documented order
        //    storeStock -> locationBalance holds.
        var lockedBalances = new LinkedHashMap<UUID, ProductVariantLocation>();
        for (var balance : storeBalances) {
            lockedBalances.put(
                    balance.getStoreLocationId(), lockBalance(productVariantId, balance.getStoreLocationId()));
        }

        // 8. Second fail-closed check (W3-D1), computed before any write: a store the aggregate
        //    promised but the locations cannot cover is left untouched instead of partially deducted.
        var locationTotal = lockedBalances.values().stream()
                .map(location -> orZero(location.getStock()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (locationTotal.compareTo(quantity) < 0) {
            throw new InsufficientStockException(productVariantId, quantity, locationTotal);
        }

        // 9. Consume: priority location first, then the FIFO order. One SALE movement per location
        //    actually consumed; a location holding nothing contributes no row.
        var remaining = quantity;
        for (var location : orderForDeduction(lockedBalances.values(), priorityLocationId)) {
            if (remaining.signum() == 0) {
                break;
            }
            var stock = orZero(location.getStock());
            var taken = stock.min(remaining);
            if (taken.signum() <= 0) {
                continue;
            }
            location.setStock(stock.subtract(taken));
            productVariantLocationRepository.save(location);
            var saleMovement = newMovement(
                    productVariantId,
                    companyStoreId,
                    location.getStoreLocationId(),
                    MovementType.SALE,
                    taken,
                    referenceType,
                    referenceId,
                    createdBy);
            inventoryMovementRepository.save(saleMovement);
            // 9b. The ledger books the same deduction, and the allocation writer records which inbound
            //     rows it consumed (decision 3). Legacy first, ledger after: the availability contract
            //     above is the gate a caller meets, and I8 in the projection trigger stays the ledger's
            //     own independent gate — it reads the ledger's position, never these numbers.
            stockLedgerWriter.bookSale(
                    booking(
                            StockLedgerWriter.MIRRORED_SOURCE_TYPE,
                            companyId,
                            companyStoreId,
                            location.getStoreLocationId(),
                            productVariantId,
                            createdBy,
                            saleMovement.getId()),
                    taken);
            remaining = remaining.subtract(taken);
        }

        // 10. The aggregate moves by the same total, in one update, never recomputed from the sum.
        storeStock.setStock(available.subtract(quantity));
        productVariantStoreStockRepository.save(storeStock);
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

        // 1. The ledger, not a fresh allocation, says what each location gave.
        var uncovered = uncoveredRemainder(referenceType, referenceId);
        if (uncovered.isEmpty()) {
            // Nothing was sold under this reference, or everything is already reversed: no-op.
            return;
        }

        // 2. Group per (variant, store) in a deterministic order, then reverse each scope with the
        //    documented lock order storeStock -> locationBalance. The ledger needs one more fact per scope:
        //    which of the sale's ledger rows are still open, because a reversal names the row it reverses
        //    and never re-derives a remainder (decision 3).
        var openSales = openLedgerSales(referenceType, referenceId);
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
     */
    public record SaleChange(
            UUID companyStoreId, UUID productVariantId, BigDecimal quantity, UUID referenceId, boolean reversal) {

        /**
         * Sells {@code quantity} of the variant in the store, under the line-level reference the
         * ledger carries (W3-D6).
         */
        public static SaleChange deduction(
                UUID companyStoreId, UUID productVariantId, BigDecimal quantity, UUID referenceId) {
            return new SaleChange(companyStoreId, productVariantId, quantity, referenceId, false);
        }

        /**
         * Gives back whatever the reference still holds, so the same line id says which sale to
         * balance; {@code quantity} plays no part and is null.
         */
        public static SaleChange reversal(UUID companyStoreId, UUID productVariantId, UUID referenceId) {
            return new SaleChange(companyStoreId, productVariantId, null, referenceId, true);
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
                        createdBy));
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

        // 4. The delta is the whole point and it is computed HERE, never in the product service, so a
        //    single owner holds the arithmetic that keeps aggregate and location equal.
        var delta = newStock.subtract(orZero(storeStock.getStock()));

        // 5. Move the locations by the same delta. A zero delta moves nothing and writes nothing:
        //    the ledger records facts, not no-ops. An increase credits the destination (nothing to
        //    draw from); a decrease is ALLOCATED like a sale (W3-D1), so it cannot be assigned to one
        //    location and drive it below zero while aggregate = SUM(locations) still holds (F18).
        if (delta.signum() > 0) {
            productVariantLocationRepository.insertBalanceIfAbsent(productVariantId, destinationLocationId);
            var location = lockBalance(productVariantId, destinationLocationId);
            location.setStock(orZero(location.getStock()).add(delta));
            productVariantLocationRepository.save(location);

            var increaseMovement = newMovement(
                    productVariantId,
                    companyStoreId,
                    destinationLocationId,
                    MovementType.ADJUSTMENT_INCREASE,
                    delta,
                    MANUAL_STOCK_EDIT_REFERENCE_TYPE,
                    null,
                    createdBy);
            inventoryMovementRepository.save(increaseMovement);
            // 5b. The ledger books the increase beside the legacy row: ADJUSTMENT_IN adds quantity, so
            //     it states a cost and a currency (ck_movement_inbound_cost), and the operator's cost is
            //     the one this store records — the signature carries none and inventing one would be a
            //     number nobody wrote down.
            bookLedgerInbound(
                    StockLedgerWriter.MIRRORED_SOURCE_TYPE,
                    companyIdOf(companyStoreId),
                    companyStoreId,
                    destinationLocationId,
                    productVariantId,
                    delta,
                    storeStock.getCostPrice(),
                    increaseMovement.getId(),
                    createdBy,
                    false);
        } else if (delta.signum() < 0) {
            applyAdjustmentDecrease(productVariantId, companyStoreId, destinationLocationId, delta.negate(), createdBy);
        }

        // 6. The aggregate takes the absolute target, never a re-derivation from the location sum:
        //    the recompute question is closed (W3-D5), and the locations already moved by the delta.
        storeStock.setStock(newStock);
        productVariantStoreStockRepository.save(storeStock);
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

        var storeBalances = productVariantLocationRepository.findByProductVariantIdAndCompanyStoreId(
                productVariantId, companyStoreId);
        var lockedBalances = new LinkedHashMap<UUID, ProductVariantLocation>();
        for (var balance : storeBalances) {
            lockedBalances.put(
                    balance.getStoreLocationId(), lockBalance(productVariantId, balance.getStoreLocationId()));
        }

        var locationTotal = lockedBalances.values().stream()
                .map(location -> orZero(location.getStock()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (locationTotal.compareTo(amount) < 0) {
            throw new InsufficientStockException(productVariantId, amount, locationTotal);
        }

        var remaining = amount;
        var companyId = companyIdOf(companyStoreId);
        for (var location : orderForDeduction(lockedBalances.values(), priorityLocationId)) {
            if (remaining.signum() == 0) {
                break;
            }
            var stock = orZero(location.getStock());
            var taken = stock.min(remaining);
            if (taken.signum() <= 0) {
                continue;
            }
            location.setStock(stock.subtract(taken));
            productVariantLocationRepository.save(location);
            var decreaseMovement = newMovement(
                    productVariantId,
                    companyStoreId,
                    location.getStoreLocationId(),
                    MovementType.ADJUSTMENT_DECREASE,
                    taken,
                    MANUAL_STOCK_EDIT_REFERENCE_TYPE,
                    null,
                    createdBy);
            inventoryMovementRepository.save(decreaseMovement);
            // One ADJUSTMENT_OUT per location drawn from, each with its allocations: an outbound row that
            // is not allocated violates I6, and the allocation writer is the only consumption path
            // (decision 3).
            stockLedgerWriter.bookAdjustmentOut(
                    booking(
                            StockLedgerWriter.MIRRORED_SOURCE_TYPE,
                            companyId,
                            companyStoreId,
                            location.getStoreLocationId(),
                            productVariantId,
                            createdBy,
                            decreaseMovement.getId()),
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
     * The per-location remainder of one reference: {@code SUM(SALE) - SUM(SALE_REVERSAL)}, keeping
     * only positive amounts. A non-positive amount is already reversed and must not be credited
     * again. Rows of any other type are ignored: only a sale and its reversal share a sale reference.
     */
    private Map<SaleBalanceKey, BigDecimal> uncoveredRemainder(String referenceType, UUID referenceId) {
        var uncovered = new LinkedHashMap<SaleBalanceKey, BigDecimal>();
        for (var movement : inventoryMovementRepository.findByReferenceTypeAndReferenceId(referenceType, referenceId)) {
            var key = new SaleBalanceKey(
                    movement.getProductVariantId(), movement.getCompanyStoreId(), movement.getStoreLocationId());
            if (movement.getMovementType() == MovementType.SALE) {
                uncovered.merge(key, orZero(movement.getQuantity()), BigDecimal::add);
            } else if (movement.getMovementType() == MovementType.SALE_REVERSAL) {
                uncovered.merge(key, orZero(movement.getQuantity()).negate(), BigDecimal::add);
            }
        }
        uncovered.values().removeIf(amount -> amount.signum() <= 0);
        return uncovered;
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
     * <p>The one case the ledger cannot express is refused loudly rather than repaired:
     * {@code V61}'s shape rule demands that a reversal move <em>exactly</em> the quantity of the row it
     * names, so a legacy remainder that is a strict part of an open sale row (a partial reversal) has no shape
     * in the ledger. Raising there is the point: a silent partial reversal would free a fraction of the rows
     * the original consumed, which is the remainder arithmetic decision 3 removes.</p>
     */
    private void reverseScope(
            UUID productVariantId,
            UUID companyStoreId,
            NavigableMap<UUID, BigDecimal> locationAmounts,
            Map<SaleBalanceKey, List<OpenSale>> openSales,
            String referenceType,
            UUID referenceId,
            String createdBy) {

        var storeStock = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreIdForUpdate(productVariantId, companyStoreId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product variant " + productVariantId + " has no stock row in store " + companyStoreId));

        var companyId = companyIdOf(companyStoreId);
        var total = BigDecimal.ZERO;
        for (var entry : locationAmounts.entrySet()) {
            var location = lockBalance(productVariantId, entry.getKey());
            var amount = entry.getValue();
            var open = openSales.getOrDefault(
                    new SaleBalanceKey(productVariantId, companyStoreId, entry.getKey()), List.of());
            requireTheWholeRemainderIsOpen(productVariantId, companyStoreId, entry.getKey(), amount, open);
            location.setStock(orZero(location.getStock()).add(amount));
            productVariantLocationRepository.save(location);
            var reversalMovement = newMovement(
                    productVariantId,
                    companyStoreId,
                    entry.getKey(),
                    MovementType.SALE_REVERSAL,
                    amount,
                    referenceType,
                    referenceId,
                    createdBy);
            inventoryMovementRepository.save(reversalMovement);
            for (var sale : open) {
                stockLedgerWriter.bookSaleReversal(
                        booking(
                                StockLedgerWriter.MIRRORED_SOURCE_TYPE,
                                companyId,
                                companyStoreId,
                                entry.getKey(),
                                productVariantId,
                                createdBy,
                                reversalMovement.getId()),
                        sale.quantity(),
                        sale.ledgerMovementId());
            }
            total = total.add(amount);
        }

        storeStock.setStock(orZero(storeStock.getStock()).add(total));
        productVariantStoreStockRepository.save(storeStock);
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
    private static List<ProductVariantLocation> orderForDeduction(
            Collection<ProductVariantLocation> lockedBalances, UUID priorityLocationId) {
        var ordered = new ArrayList<ProductVariantLocation>(lockedBalances.size());
        if (priorityLocationId != null) {
            lockedBalances.stream()
                    .filter(location -> priorityLocationId.equals(location.getStoreLocationId()))
                    .forEach(ordered::add);
        }
        lockedBalances.stream().filter(location -> !ordered.contains(location)).forEach(ordered::add);
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
     * <p>{@code sourceType} is required, and every call site in this class states
     * {@link StockLedgerWriter#MIRRORED_SOURCE_TYPE} explicitly: the dual-write window's transition shape is
     * visible where each fact is booked instead of being implied by the writer (see
     * {@link StockLedgerWriter#MIRRORED_SOURCE_TYPE} and the re-cut entry of
     * {@code odd/tasks/inventory-ledger.md} that defines T5a/T5b/T5c, where T5c stops the legacy writes and
     * {@code inventory_movements} drops in T6).</p>
     */
    private static StockLedgerWriter.Booking booking(
            String sourceType,
            UUID companyId,
            UUID companyStoreId,
            UUID storeLocationId,
            UUID productVariantId,
            String createdBy,
            UUID sourceId) {
        return new StockLedgerWriter.Booking(
                companyId, companyStoreId, storeLocationId, productVariantId, createdBy, sourceType, sourceId);
    }

    /**
     * Books an inbound movement (a receipt or the increase side of a manual edit) beside the legacy row that
     * states the same fact.
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
        var booking =
                booking(sourceType, companyId, companyStoreId, storeLocationId, productVariantId, createdBy, sourceId);
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
     * <p>Read from the legacy rows the reversal already reads, each resolved to the ledger row that mirrors it
     * — that is what makes the reversal name the exact rows instead of re-deriving a remainder. A legacy sale
     * that has no ledger twin predates this slice's writers (the deploy window), and it is refused rather than
     * guessed at: the ledger holds no movement to reverse, and inventing one would record a fact nobody
     * observed.</p>
     */
    private Map<SaleBalanceKey, List<OpenSale>> openLedgerSales(String referenceType, UUID referenceId) {
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
            var ledgerMovementId = stockLedgerWriter
                    .mirroredMovementId(sale.getId(), StockLedgerWriter.SALE)
                    .orElseThrow(() -> new IllegalStateException("The legacy ledger holds SALE movement " + sale.getId()
                            + " of reference " + referenceType + "/" + referenceId + " and the stock ledger holds no"
                            + " movement that mirrors it, so it cannot be reversed: a sale booked before this"
                            + " slice's writers existed has no ledger row to name (ADR-0001 decision 3)"));
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

    /**
     * Refuses a remainder the ledger cannot express as a set of whole reversals.
     *
     * <p>{@code V61} allows a reversal to move exactly the quantity of the row it names, and no more than one
     * reversal per row. If the legacy remainder at one location is not the sum of the sale rows still open
     * there, the legacy path is reversing a part of a sale — the remainder arithmetic decision 3 removes — and
     * the ledger has no shape for it. The message says so instead of booking a reversal that frees the wrong
     * rows.</p>
     */
    private static void requireTheWholeRemainderIsOpen(
            UUID productVariantId, UUID companyStoreId, UUID storeLocationId, BigDecimal amount, List<OpenSale> open) {
        var openTotal = open.stream().map(OpenSale::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (openTotal.compareTo(amount) != 0) {
            throw new IllegalStateException("The legacy ledger holds a remainder of " + amount + " for variant "
                    + productVariantId + " in store " + companyStoreId + " at location " + storeLocationId
                    + ", and the stock ledger holds " + openTotal
                    + " of sale rows no reversal names there. A reversal must move exactly the quantity of the row"
                    + " it names and may happen once per row (V61), so a partial remainder has no ledger shape: a"
                    + " partial correction is a new movement, not a reversal (ADR-0001 decision 3)");
        }
    }

    private static InventoryMovement newMovement(
            UUID productVariantId,
            UUID companyStoreId,
            UUID storeLocationId,
            MovementType movementType,
            BigDecimal quantity,
            String referenceType,
            UUID referenceId,
            String createdBy) {
        return InventoryMovement.builder()
                .productVariantId(productVariantId)
                .companyStoreId(companyStoreId)
                .storeLocationId(storeLocationId)
                .movementType(movementType)
                .quantity(quantity)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .createdBy(createdBy)
                .build();
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
