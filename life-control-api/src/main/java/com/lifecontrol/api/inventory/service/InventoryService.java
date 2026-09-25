package com.lifecontrol.api.inventory.service;

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
 * <p>Workstream W2a ships the balances, the append-only ledger and this mutator. Nothing calls it
 * yet: the goods-receipt document, its endpoints and the {@code lc-receiving} role arrive with W2c,
 * which is why this slice changes no existing behaviour.</p>
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
 * <h2>Interim contract (until the sales rework, workstream W3, lands)</h2>
 * <ol>
 *   <li><b>Sales still bypasses locations.</b> {@code SalesOrderService.applyStockChanges} keeps
 *       subtracting the sold quantity from the per-store {@code product_variant_store_stock.stock}
 *       with no location dimension, because W3 is deferred.</li>
 *   <li><b>Location stock overcounts.</b> {@code product_variant_locations.stock} is therefore
 *       higher than the sellable aggregate by everything sold since receipt, and must not be
 *       presented as sellable availability until W3.</li>
 *   <li><b>The ledger is partial.</b> It holds only {@link MovementType#RECEIPT} rows until W3
 *       starts writing sale movements, so it is a partial history and not yet a complete stock
 *       audit.</li>
 *   <li><b>W3 owes the reconciliation</b> of the location balances against the aggregate, and the
 *       switch of {@link #applyReceipt} from additive to recompute semantics.</li>
 * </ol>
 */
@Service
@Transactional
public class InventoryService {

    private static final Logger logger = LoggerFactory.getLogger(InventoryService.class);

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
        //    both incremented here; neither may be re-derived from the other. Until W3 makes the
        //    sales path location-aware, SalesOrderService.applyStockChanges subtracts the sold
        //    quantity from `product_variant_store_stock.stock` without touching any location row, so
        //    the sellable row is legitimately BELOW the sum of the location balances:
        //    sellable = SUM(locations) - everything sold since receipt. Recomputing the sellable
        //    stock from the location sum would silently resurrect stock that sales already sold.
        //    W3 owes the reconciliation and the switch of this method to recompute semantics.
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
     * {@link InsufficientStockException}.</p>
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
     * @throws IllegalArgumentException when {@code quantity} is not greater than zero, or when the
     *     variant has no stock row in {@code companyStoreId}
     * @throws InsufficientStockException when the store cannot cover {@code quantity}, first against
     *     the aggregate and then against the sum of the store's location balances
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

        // 3. FIRST lock: the per-store stock row, the serialization point of every stock mover.
        var storeStock = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreIdForUpdate(productVariantId, companyStoreId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product variant " + productVariantId + " has no stock row in store " + companyStoreId));

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
