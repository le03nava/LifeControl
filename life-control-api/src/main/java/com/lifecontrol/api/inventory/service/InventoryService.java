package com.lifecontrol.api.inventory.service;

import com.lifecontrol.api.inventory.exception.StoreInventorySettingsNotFoundException;
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
 * variant definition is read without a lock only to reject a missing or disabled variant.</p>
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

    private final ProductVariantRepository productVariantRepository;
    private final ProductVariantStoreStockRepository productVariantStoreStockRepository;
    private final ProductVariantLocationRepository productVariantLocationRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final StoreInventorySettingsRepository storeInventorySettingsRepository;

    public InventoryService(
            ProductVariantRepository productVariantRepository,
            ProductVariantStoreStockRepository productVariantStoreStockRepository,
            ProductVariantLocationRepository productVariantLocationRepository,
            InventoryMovementRepository inventoryMovementRepository,
            StoreInventorySettingsRepository storeInventorySettingsRepository) {
        this.productVariantRepository = productVariantRepository;
        this.productVariantStoreStockRepository = productVariantStoreStockRepository;
        this.productVariantLocationRepository = productVariantLocationRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.storeInventorySettingsRepository = storeInventorySettingsRepository;
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
     * @throws ProductVariantNotFoundException when the variant does not exist or is disabled
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

        // 2. The definition must exist and be enabled. Unlike before the split this read takes NO
        //    lock: the definition carries no stock, so it is not the serialization point any more.
        //    The enabled filter preserves the old ProductVariantNotFoundException contract.
        if (productVariantRepository
                .findById(productVariantId)
                .filter(variant -> Boolean.TRUE.equals(variant.getEnabled()))
                .isEmpty()) {
            throw new ProductVariantNotFoundException(productVariantId);
        }

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
     * @throws ProductVariantNotFoundException when the variant does not exist or is disabled
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

        // 2. The definition must exist and be enabled, read WITHOUT a lock: it carries no stock.
        if (productVariantRepository
                .findById(productVariantId)
                .filter(variant -> Boolean.TRUE.equals(variant.getEnabled()))
                .isEmpty()) {
            throw new ProductVariantNotFoundException(productVariantId);
        }

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
            inventoryMovementRepository.save(newMovement(
                    productVariantId,
                    companyStoreId,
                    location.getStoreLocationId(),
                    MovementType.SALE,
                    taken,
                    referenceType,
                    referenceId,
                    createdBy));
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
                    referenceType,
                    referenceId,
                    createdBy);
        }
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

            inventoryMovementRepository.save(newMovement(
                    productVariantId,
                    companyStoreId,
                    destinationLocationId,
                    MovementType.ADJUSTMENT_INCREASE,
                    delta,
                    MANUAL_STOCK_EDIT_REFERENCE_TYPE,
                    null,
                    createdBy));
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
            inventoryMovementRepository.save(newMovement(
                    productVariantId,
                    companyStoreId,
                    location.getStoreLocationId(),
                    MovementType.ADJUSTMENT_DECREASE,
                    taken,
                    MANUAL_STOCK_EDIT_REFERENCE_TYPE,
                    null,
                    createdBy));
            remaining = remaining.subtract(taken);
        }
    }

    /**
     * Takes the pessimistic lock on one {@code (variant, store)} stock row without moving it, so a
     * caller that runs several stock operations in one batch can take every row in a deterministic
     * order before it runs any of them.
     *
     * <p>Such a caller must run its reference-scoped reversals before its deductions, because a
     * reversal that reads the ledger after a same-batch deduction of the same reference would credit
     * the fresh sale back. That ordering can invert the sorted key order of the rows an unordered
     * batch would take, so locking every row up front keeps the acquisition order ascending and the
     * deadlock invariant intact. It is the same first lock every mover takes, which preserves the
     * documented {@code storeStock -> locationBalance} order, and re-locking an already-held row
     * inside the same transaction is a no-op. A row that does not exist is simply not locked: the
     * operation that follows raises its own exception.</p>
     */
    public void lockStoreStock(UUID productVariantId, UUID companyStoreId) {
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
     */
    private void reverseScope(
            UUID productVariantId,
            UUID companyStoreId,
            NavigableMap<UUID, BigDecimal> locationAmounts,
            String referenceType,
            UUID referenceId,
            String createdBy) {

        var storeStock = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreIdForUpdate(productVariantId, companyStoreId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product variant " + productVariantId + " has no stock row in store " + companyStoreId));

        var total = BigDecimal.ZERO;
        for (var entry : locationAmounts.entrySet()) {
            var location = lockBalance(productVariantId, entry.getKey());
            var amount = entry.getValue();
            location.setStock(orZero(location.getStock()).add(amount));
            productVariantLocationRepository.save(location);
            inventoryMovementRepository.save(newMovement(
                    productVariantId,
                    companyStoreId,
                    entry.getKey(),
                    MovementType.SALE_REVERSAL,
                    amount,
                    referenceType,
                    referenceId,
                    createdBy));
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

    private ProductVariantLocation lockBalance(UUID productVariantId, UUID storeLocationId) {
        return productVariantLocationRepository
                .findByProductVariantIdAndStoreLocationIdForUpdate(productVariantId, storeLocationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Product variant location balance not found for variant " + productVariantId
                                + " and store location " + storeLocationId + " after it was locked or created"));
    }
}
