package com.lifecontrol.api.inventory.ledger;

import com.lifecontrol.api.inventory.exception.InsufficientStockException;
import com.lifecontrol.api.inventory.exception.StoreInventorySettingsNotFoundException;
import com.lifecontrol.api.inventory.model.StockPosition;
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
 * The stock engine: it decides <em>how much</em> moves, <em>where</em> and <em>under which identity</em>, and books
 * every decided fact through {@link StockLedgerWriter}.
 *
 * <p>This is what {@code StockLedgerEngine} became when the mirror was retired ({@code
 * odd/tasks/ledger-mirror-retirement.md}, T4). What it keeps is the judgment — the 409 a sale is refused by, the
 * priority-then-FIFO split of a sale, the reversal that names the rows it gives back, and the delta a manual edit
 * is turned into. What it no longer touches is the legacy ledger: no {@code inventory_movements} row, no
 * {@code product_variant_locations.stock}, no {@code product_variant_store_stock.stock}, and no lock on either.
 * ADR-0001 decision 1 makes {@code stock_movement} the one fact per row, {@code V61}'s trigger the one writer of
 * the projections, and {@code stock_position} the one authority on quantity — so this class writes movements and
 * reads projections, and nothing else.</p>
 *
 * <h2>Four entry points, three callers</h2>
 * <p>The goods receipt ({@link #applyReceipt}), the sale and its reversal ({@link #applySaleDeduction} /
 * {@link #applySaleReversal} through {@link #applySaleBatch}) and the manual per-store edit
 * ({@link #applyStockAdjustment}). Each keeps the signature and the error contract it had before the mirror died,
 * because the callers and the screens are not part of this unit: a variant that is missing, disabled or another
 * tenant's is {@link ProductVariantNotFoundException} (404), a quantity the ledger cannot cover is
 * {@link InsufficientStockException} (409), a malformed argument is {@link IllegalArgumentException} (400), and a
 * store without the settings row a manual edit needs is {@link StoreInventorySettingsNotFoundException}.</p>
 *
 * <h2>Where serialization happens now that no legacy row is locked</h2>
 * <p>It moved to the row the decision is about: {@code stock_position}. A sale takes it
 * {@code FOR UPDATE} before it reads the split, and a manual edit takes it before it computes its delta
 * ({@code StockPositionRepository#findByCompanyStoreIdAndProductVariantIdForUpdate}), so two movers of one
 * {@code (store, variant)} queue on the row they are both about to change — and that is the same row
 * {@code V61}'s trigger locks again when it writes the projection, for every writer that reaches the table
 * including a receipt, which takes no gate of its own. The legacy {@code product_variant_store_stock} lock is
 * gone with the column it protected: it serialized by accident of the dual-write window, and a gate that is only
 * correct while an unrelated lock is held fails silently the day that lock goes.</p>
 *
 * <p>Two consequences follow, both named rather than discovered. A {@code (store, variant)} with no position row
 * takes no lock and is refused as zero by the sale's gate — the ledger never moved it, so there is no quantity to
 * sell and no row to queue on. And the batch still hands its work to the engine in ascending {@code (store,
 * variant)} order, because that is the order in which the position locks are taken: two sales of two keys cannot
 * deadlock on each other.</p>
 *
 * <h2>What a reversal resolves, and what it no longer resolves</h2>
 * <p>What a reversal credits per location is the set of {@code SALE} rows the ledger still holds open, read by the
 * source the movements carry ({@code StockLedgerWriter#openSales}). The pre-{@code V73} fallback that found a sale
 * by the {@code inventory_movements} row it mirrored is gone with the table: from the drop on, nothing maps a
 * legacy row to a ledger row, and the drop itself refuses while an unreversed mirrored sale is still in the tree
 * ({@code odd/tasks/ledger-mirror-retirement.md}, D-1). A reference whose stock was sold before the deploy and
 * after it now reverses through the one identity that exists — the sale line — which is why the mixed-identity
 * read that used to merge two shapes has nothing left to merge.</p>
 *
 * <h2>Tenant scope, and why a missing one is loud</h2>
 * <p>The ledger's tables are armed on {@code tenant_table} and {@code FORCE}d ({@code V60}), so every statement
 * here runs as {@code lifecontrol_app} under ADR-0005's policy and needs the transaction's scope. This class never
 * states a scope and never bypasses one: it joins the caller's transaction, whose boundary installed the scope
 * ({@code TenantScopeJpaTransactionManager}). The company a movement carries is derived from the store that owns
 * it ({@link CompanyStoreOwnerResolver}), never from the request, so a movement and every store-scoped read agree
 * on which company a store belongs to.</p>
 */
@Service
@Transactional
public class StockLedgerEngine {

    private static final Logger logger = LoggerFactory.getLogger(StockLedgerEngine.class);

    /**
     * Why an inbound movement can be booked at zero cost: {@code ck_movement_inbound_cost} demands a cost and a
     * currency on every row that adds quantity, and {@code product_variant_store_stock.cost_price} is the only cost
     * this deployment records. A row with no recorded cost is booked at zero with the reason stated on the row,
     * exactly as {@code V62}'s backfill decided for the same fact.
     */
    private static final String UNRECORDED_COST_REASON =
            "no cost is recorded for this variant in this store, so the inbound movement is booked at 0";

    private final ProductVariantRepository productVariantRepository;
    private final ProductVariantStoreStockRepository productVariantStoreStockRepository;
    private final StoreInventorySettingsRepository storeInventorySettingsRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CompanyStoreOwnerResolver companyStoreOwnerResolver;
    private final StockLedgerWriter stockLedgerWriter;
    private final StockPositionRepository stockPositionRepository;

    public StockLedgerEngine(
            ProductVariantRepository productVariantRepository,
            ProductVariantStoreStockRepository productVariantStoreStockRepository,
            StoreInventorySettingsRepository storeInventorySettingsRepository,
            CompanyStoreRepository companyStoreRepository,
            CompanyStoreOwnerResolver companyStoreOwnerResolver,
            StockLedgerWriter stockLedgerWriter,
            StockPositionRepository stockPositionRepository) {
        this.productVariantRepository = productVariantRepository;
        this.productVariantStoreStockRepository = productVariantStoreStockRepository;
        this.storeInventorySettingsRepository = storeInventorySettingsRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.companyStoreOwnerResolver = companyStoreOwnerResolver;
        this.stockLedgerWriter = stockLedgerWriter;
        this.stockPositionRepository = stockPositionRepository;
    }

    /**
     * Receives {@code quantity} of {@code productVariantId} at {@code storeLocationId}: one {@code RECEIPT}
     * movement whose source is the receipt line.
     *
     * <p>The movement carries the cost this store records for the variant and the company's operating currency,
     * because {@code ck_movement_inbound_cost} requires both on every row that adds quantity. The projections
     * ({@code stock_balance}, {@code stock_position}, {@code stock_valuation}) follow from it by trigger, so there
     * is nothing else to write: the store's sellable row is not maintained here, and neither is the location
     * balance.</p>
     *
     * @param quantity signed amount received; must be greater than zero
     * @param referenceType the source discriminator the movement states
     * @param referenceId identity of that source — for a receipt, the {@code goods_receipt_items} row
     * @param createdBy username of the receiver, when known
     * @throws IllegalArgumentException when {@code quantity} is not greater than zero, or when the variant has no
     *     stock row in {@code companyStoreId}
     * @throws ProductVariantNotFoundException when the variant does not exist, is disabled, or belongs to a
     *     company other than the one that owns {@code companyStoreId}
     * @throws CompanyStoreNotFoundException when the store does not exist, so no company can be derived from it
     */
    public void applyReceipt(
            UUID productVariantId,
            UUID companyStoreId,
            UUID storeLocationId,
            BigDecimal quantity,
            String referenceType,
            UUID referenceId,
            String createdBy) {

        // 1. Reject the invalid quantity before touching the database: an invalid receipt must not book a
        //    movement or read a store row.
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Receipt quantity must be greater than zero, but was " + quantity);
        }

        // 2. The definition must exist, be enabled and belong to the company that owns the store. It is read
        //    WITHOUT a lock: the definition carries no stock, and since the legacy column is gone there is no
        //    serialization point here to take. The company predicate is what keeps a receipt from moving another
        //    tenant's stock: the store argument says which tenant may be touched, while the variant id says
        //    nothing at all, and the schema cannot forbid the pair because company_stores has no company_id.
        if (productVariantRepository
                .findByIdAndCompanyIdAndEnabledTrue(productVariantId, companyIdOf(companyStoreId))
                .isEmpty()) {
            throw new ProductVariantNotFoundException(productVariantId);
        }

        // 3. The tenant every booked movement carries, derived once from the store that owns it.
        var companyId = companyIdOf(companyStoreId);

        // 4. An absent stock row is the store-membership failure: the variant was never registered in this store,
        //    so there is nothing to attribute the receipt to and nothing that records what the goods cost. It is
        //    deliberately asymmetric with the sale's gate, which refuses an unstocked variant as zero sellable
        //    stock (409): receiving into a store the variant is not stocked in is a placement error (400), while
        //    selling it is an empty balance.
        var storeStock = productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(productVariantId, companyStoreId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product variant " + productVariantId + " has no stock row in store " + companyStoreId));

        // 5. The movement, and that is the whole write. Its source is the caller's pair, not a mirrored row's id:
        //    a receipt line is its own identity (StockLedgerWriter.RECEIPT_LINE_SOURCE_TYPE).
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
     * Deducts {@code quantity} of {@code productVariantId} from {@code companyStoreId}, booking one {@code SALE}
     * movement per store location consumed, all with a positive quantity (W3-D7).
     *
     * <p>The store's priority location is {@code store_inventory_settings.sales_location_id}: the quantity drains
     * there first and the remainder spills over the store's other balances in the order the ledger reports them,
     * which is the legacy FIFO order (oldest {@code product_variant_locations} row first,
     * {@code store_location_id} as the deterministic tie-break). A store with no settings row falls back to plain
     * FIFO and logs exactly one warning (W3-D8): the precondition of a sale is the position, already validated
     * below, so a missing configuration row must not become a store-wide sales outage.</p>
     *
     * <p><b>The gate is the projection, and it holds that row's lock.</b> {@code stock_position} is the authority
     * ADR-0001 decision 1 makes, so a sale is refused by it — as {@link InsufficientStockException}, the 409 the
     * Angular POS already handles, not as the database error {@code V61}'s invariant I8 raises when an outbound
     * movement the ledger cannot cover reaches the trigger. The locking read is what serializes two deductions of
     * one key; an absent row takes no lock and is used as zero, so a key the ledger never moved can never
     * authorize a sale. I8 keeps its own {@code FOR UPDATE} as the ledger's last gate for every writer that
     * reaches the table; the two are one lock on the database inside one transaction, and what is not duplicated
     * is the audience.</p>
     *
     * <p><b>A second fail-closed check runs before any write</b> (W3-D1): the location balances the ledger reports
     * must together cover the quantity. Where the position promises more than the locations hold — a state the
     * migration's own reconciliation reports and nothing in this class can produce — the store is left untouched
     * instead of partially deducted.</p>
     *
     * @param quantity signed amount sold; must be greater than zero
     * @param referenceType source discriminator carrying the line-level provenance of the sale, which is also the
     *     {@code source_type} every booked movement states
     * @param referenceId identity of that source, which is the {@code source_id} of every movement
     * @param createdBy username of the seller, when known
     * @param writeSeq which write of that source this deduction is ({@code sales_order_items.write_seq},
     *     {@code V73}); the ledger keys the movement on it, so two writes of one line are two legal rows
     * @throws IllegalArgumentException when {@code quantity} is not greater than zero
     * @throws InsufficientStockException when the position cannot cover {@code quantity} — including when the store
     *     does not stock the variant at all, which is zero — or when the locations cannot
     * @throws ProductVariantNotFoundException when the variant does not exist, is disabled, or belongs to a company
     *     other than the one that owns {@code companyStoreId}
     * @throws CompanyStoreNotFoundException when the store does not exist, so no company can be derived from it
     */
    public void applySaleDeduction(
            UUID productVariantId,
            UUID companyStoreId,
            BigDecimal quantity,
            String referenceType,
            UUID referenceId,
            String createdBy,
            long writeSeq) {

        // 1. Reject the invalid quantity before touching the database.
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Sale quantity must be greater than zero, but was " + quantity);
        }

        // 2. The definition must exist, be enabled and belong to the store's company, read WITHOUT a lock: it
        //    carries no stock. Asking by bare id would let a sale of one tenant's store drain a balance row that
        //    names another tenant's variant — a pair the schema cannot forbid, since company_stores carries no
        //    company_id.
        if (productVariantRepository
                .findByIdAndCompanyIdAndEnabledTrue(productVariantId, companyIdOf(companyStoreId))
                .isEmpty()) {
            throw new ProductVariantNotFoundException(productVariantId);
        }

        // 3. The tenant every booked movement carries, derived once from the store that owns it.
        var companyId = companyIdOf(companyStoreId);

        // 4. THE GATE, AND THE ONLY LOCK THIS METHOD TAKES. The position row is read FOR UPDATE, so two
        //    deductions of one key serialize on the row the decision is about; an absent row is zero (fail-closed)
        //    and refuses with the same 409 a zero quantity produces.
        var position = orZero(stockPositionRepository
                .findByCompanyStoreIdAndProductVariantIdForUpdate(companyStoreId, productVariantId)
                .map(StockPosition::getQuantity)
                .orElse(null));
        if (position.compareTo(quantity) < 0) {
            throw new InsufficientStockException(productVariantId, quantity, position);
        }

        // 5. The priority location comes from the store's settings. A missing row is a FIFO fallback with one
        //    warning, never a failure (W3-D8).
        UUID priorityLocationId = resolveSalesLocationId(companyStoreId);

        // 6. How much is where: the ledger's projection per location, in the legacy FIFO order.
        var storedByLocation = new LinkedHashMap<UUID, BigDecimal>();
        for (var balance : stockLedgerWriter.openBalances(companyStoreId, productVariantId)) {
            storedByLocation.put(balance.storeLocationId(), orZero(balance.quantity()));
        }

        // 7. Second fail-closed check (W3-D1), computed before any write.
        var locationTotal = storedByLocation.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (locationTotal.compareTo(quantity) < 0) {
            throw new InsufficientStockException(productVariantId, quantity, locationTotal);
        }

        // 8. Consume: priority location first, then the FIFO order. One SALE movement per location actually
        //    consumed; a location holding nothing contributes no row. The projection trigger is what moves every
        //    balance from here.
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
     * {@code (referenceType, referenceId)}, credits back exactly what it gave.
     *
     * <p>The reversal never re-runs the priority allocation (W3-D6). Re-allocating would restore a spilled sale
     * into the priority location and drift the distribution permanently while the aggregate stayed correct. What
     * it credits is <b>the set of {@code SALE} rows the ledger still holds open</b>: there is no remainder
     * arithmetic left to do, and nothing left to compare it against — a second reversal finds no open row and is a
     * no-op, so the operation is idempotent by construction.</p>
     *
     * <p>Because the locations come from the ledger, the store's settings row is irrelevant here and a store
     * without one reverses normally (W3-D8), with no warning.</p>
     *
     * @param referenceType source discriminator of the sale, as written by the deduction
     * @param referenceId identity of that source
     * @param createdBy username of the actor, when known
     * @throws IllegalArgumentException when the reference is missing, or when a store the ledger points at has no
     *     stock row
     */
    public void applySaleReversal(String referenceType, UUID referenceId, String createdBy) {

        if (referenceType == null || referenceType.isBlank() || referenceId == null) {
            throw new IllegalArgumentException("A sale reversal needs the reference of the sale it reverses, but got"
                    + " referenceType=" + referenceType + ", referenceId=" + referenceId);
        }

        // 1. The ledger says what each location gave: the rows still open are what a reversal credits.
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

        // 2. Group per (variant, store) in a deterministic order — ascending (store, variant, location), the same
        //    order a batch takes its position locks in.
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
     * One piece of sale work of a batch, keyed by the {@code (store, variant)} row the batch orders its work by.
     * A {@code reversal} gives the reference back; otherwise the change sells {@code quantity} under it.
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
         * Gives back whatever the reference still holds, so the same line id says which sale to balance;
         * {@code quantity} plays no part and is null.
         */
        public static SaleChange reversal(UUID companyStoreId, UUID productVariantId, UUID referenceId) {
            return new SaleChange(
                    companyStoreId, productVariantId, null, referenceId, true, StockLedgerWriter.SINGLE_WRITE);
        }
    }

    /** Composite identity of a per-store stock row: the batch's sort key. */
    private record StoreVariantKey(UUID companyStoreId, UUID productVariantId) implements Comparable<StoreVariantKey> {

        @Override
        public int compareTo(StoreVariantKey other) {
            var storeOrder = companyStoreId.compareTo(other.companyStoreId);
            return storeOrder != 0 ? storeOrder : productVariantId.compareTo(other.productVariantId);
        }
    }

    /**
     * Applies one batch of sale work, owning the two ordering rules instead of lending them to the caller (F-1 of
     * {@code odd/tasks/sales-location-aware-stock.md}).
     *
     * <p><b>Every reversal runs before every deduction</b>, and the batch runs its work in ascending {@code
     * (store, variant)} order. The first rule is semantic: a reversal is reference-scoped, so it credits whatever
     * the line's ledger reference still holds and a deduction of the same reference running first would put a
     * {@code SALE} row in front of it — the reversal would credit the fresh sale back. A variant change registers
     * the reversal of the old variant and the deduction of the new one under one line reference and two different
     * keys, so a batch run in its input order would let the deduction win. The second rule is the lock order: the
     * engine takes the {@code stock_position} row of each key it touches, so the acquisition order is ascending and
     * deadlock-free even though the work runs in a different order. The caller's order is therefore irrelevant
     * here: {@code changes} may name the deduction of a reference before its reversal.</p>
     *
     * @param referenceType source discriminator carrying the line-level provenance of every sale in the batch
     *     (W3-D6)
     * @param createdBy username of the actor, when known
     * @param changes the batch, in any order; an empty batch writes no movement
     * @throws IllegalArgumentException as {@link #applySaleDeduction} and {@link #applySaleReversal}
     * @throws InsufficientStockException as {@link #applySaleDeduction}
     * @throws ProductVariantNotFoundException as {@link #applySaleDeduction}
     */
    public void applySaleBatch(String referenceType, String createdBy, List<SaleChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        var ordered = changes.stream()
                .sorted(Comparator.comparing(StockLedgerEngine::keyOf))
                .toList();

        // 1. Every reversal before every deduction.
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
     * Sets the per-store sellable stock to {@code newStock}: the absolute value the per-store editor states,
     * turned into the delta the ledger records (W3-D14).
     *
     * <p>The editor sends an ABSOLUTE value, so the engine derives the delta itself — {@code newStock − current
     * position} — and books it. The current number comes from {@code stock_position}, read <b>FOR UPDATE</b>: the
     * number the delta is taken from must not move under it, and the projection is the only quantity ADR-0001
     * leaves standing. An increase credits the store's configured {@code sales_location_id}, because there is
     * nothing to draw from and the operator is asserting a total rather than naming a shelf. A decrease is
     * allocated over the store's locations exactly as a sale is (W3-D1) — the sales location first, then the
     * remaining balances in FIFO order — so no location is driven below zero, and one movement explains each
     * location it draws from.</p>
     *
     * <p>A store with no {@code store_inventory_settings} row is REFUSED with
     * {@link StoreInventorySettingsNotFoundException} rather than guessed: F16 measured the divergence this method
     * exists to stop, and a guessed destination would reproduce it. This is the opposite of the sale deduction's
     * FIFO fallback (W3-D8): a missing configuration row must not turn a sale into an outage, but a hand edit is an
     * explicit act of placement and may require the store to be configured first.</p>
     *
     * <p>The type carries the direction (W3-D7): {@code ADJUSTMENT_IN} for an increase and {@code ADJUSTMENT_OUT}
     * for a decrease, both with a positive quantity. A manual edit names no document, so a movement's source is
     * its own id (D-2) — nothing retries a manual edit, so its identity has to refuse nothing.</p>
     *
     * <p>A target equal to the current position is a no-op on the ledger and writes nothing, while the settings
     * refusal still applies, so the precondition does not depend on the delta.</p>
     *
     * @param newStock absolute sellable stock to set; must not be negative
     * @param createdBy username of the operator who made the edit, when known
     * @return the resulting stock, which is {@code newStock}
     * @throws IllegalArgumentException when {@code newStock} is null or negative, or when the variant has no stock
     *     row in {@code companyStoreId}
     * @throws StoreInventorySettingsNotFoundException when the store has no settings row, so no destination
     *     location exists
     * @throws InsufficientStockException when the locations cannot cover a decrease; unreachable through the
     *     application while the projection is the sum of the ledger's rows
     */
    public BigDecimal applyStockAdjustment(
            UUID productVariantId, UUID companyStoreId, BigDecimal newStock, String createdBy) {

        // 1. Reject an invalid target before touching the database.
        if (newStock == null || newStock.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "Manually set stock must be greater than or equal to zero, but was " + newStock);
        }

        // 2. The destination comes from the store's settings, and its absence is a refusal (W3-D14): guessing
        //    where the goods sit is how the aggregate and the locations drift apart (F16).
        var settings = storeInventorySettingsRepository
                .findByCompanyStoreId(companyStoreId)
                .orElseThrow(() -> new StoreInventorySettingsNotFoundException(companyStoreId));
        var destinationLocationId = settings.getSalesLocationId();

        // 3. Store membership: the editor creates the row before delegating, so its absence here is a caller
        //    error. The read does not lock — the position row below is the serialization point.
        productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(productVariantId, companyStoreId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product variant " + productVariantId + " has no stock row in store " + companyStoreId));

        // 4. The delta is the whole point and it is computed HERE, never in the product service, so a single owner
        //    holds the arithmetic. The current number is the position, read under its own lock.
        var currentStock = orZero(stockPositionRepository
                .findByCompanyStoreIdAndProductVariantIdForUpdate(companyStoreId, productVariantId)
                .map(StockPosition::getQuantity)
                .orElse(null));
        var delta = newStock.subtract(currentStock);

        // 5. A zero delta moves nothing and writes nothing: the ledger records facts, not no-ops.
        if (delta.signum() > 0) {
            // 5a. One ADJUSTMENT_IN row that states its own identity and its cost: an increase adds quantity, so
            //     ck_movement_inbound_cost requires the cost this store records and the company's currency.
            bookLedgerInbound(
                    StockLedgerWriter.MANUAL_ADJUSTMENT_SOURCE_TYPE,
                    companyIdOf(companyStoreId),
                    companyStoreId,
                    destinationLocationId,
                    productVariantId,
                    delta,
                    storedCostPrice(productVariantId, companyStoreId),
                    UUID.randomUUID(),
                    createdBy,
                    false);
        } else if (delta.signum() < 0) {
            applyAdjustmentDecrease(productVariantId, companyStoreId, destinationLocationId, delta.negate(), createdBy);
        }

        return newStock;
    }

    /**
     * Allocates a manual decrease over the store's locations instead of assigning it to one: the store's sales
     * location first, then the remaining balances in the FIFO order the ledger reports — the same order and the
     * same {@link #orderForDeduction} helper a sale uses (W3-D1), because a second allocation rule for one concept
     * is how two owners of the same number start. One {@code ADJUSTMENT_OUT} movement is written per location
     * actually drawn from, so the ledger explains every balance the edit moved, and each states its own id as its
     * source (D-2).
     *
     * <p>Failure is closed as the sale's second check is: the locations must together cover the decrease. Once the
     * projection is the sum of the ledger's rows the guard is unreachable through the application — the target is
     * non-negative, so the decrease can never exceed the position — and it exists for the state a writer outside
     * the ledger would produce.</p>
     */
    private void applyAdjustmentDecrease(
            UUID productVariantId, UUID companyStoreId, UUID priorityLocationId, BigDecimal amount, String createdBy) {

        var storedByLocation = new LinkedHashMap<UUID, BigDecimal>();
        for (var balance : stockLedgerWriter.openBalances(companyStoreId, productVariantId)) {
            storedByLocation.put(balance.storeLocationId(), orZero(balance.quantity()));
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
            // One ADJUSTMENT_OUT per location drawn from, each with its allocations: an outbound row that is not
            // allocated violates I6, and the allocation writer is the only consumption path (decision 3). Its
            // source is its own id, for the same reason the increase states (D-2).
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
     * Credits one {@code (variant, store)} scope back to the locations of its remainder.
     *
     * <p>There can be more ledger reversals than the old engine wrote legacy rows at one location: a line that was
     * sold twice — a grown quantity sells the difference under the same reference — holds two open rows there, and
     * a reversal has to name every {@code SALE} row it gives back. That is legal in the ledger by construction:
     * {@code uq_movement_source} is partial on {@code reverses_movement_id IS NULL}, so a reversal row is not in
     * it, and {@code uq_movement_one_reversal} is what bounds the set instead — at most one reversal per reversed
     * row. Each reversal states the caller's source and moves exactly the quantity of the row it names
     * ({@code V61}).</p>
     */
    private void reverseScope(
            UUID productVariantId,
            UUID companyStoreId,
            NavigableMap<UUID, BigDecimal> locationAmounts,
            Map<SaleBalanceKey, List<OpenSale>> openSales,
            String referenceType,
            UUID referenceId,
            String createdBy) {

        // Store membership: the ledger points at this store, so a missing stock row is a state the API states
        // rather than a lock it needs. The reversal itself serializes on the projection's own locks.
        productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(productVariantId, companyStoreId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product variant " + productVariantId + " has no stock row in store " + companyStoreId));

        var companyId = companyIdOf(companyStoreId);
        for (var entry : locationAmounts.entrySet()) {
            var open = openSales.getOrDefault(
                    new SaleBalanceKey(productVariantId, companyStoreId, entry.getKey()), List.of());
            for (var sale : open) {
                // The reversal names the sale line as its source and the movement it corrects in
                // reverses_movement_id. A reversal row is outside uq_movement_source, so it needs no write
                // sequence of its own.
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
     * The store's configured sales location, or {@code null} when the store has no settings row. A missing row is
     * a FIFO fallback with exactly one warning, never a failure (W3-D8).
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
     * The consumption order of the balances: the store's priority location first, then the order the ledger
     * reported (FIFO). A priority location without a balance of the variant is simply absent from the list.
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
     * One remainder the ledger still holds open: a {@code SALE} row no reversal names yet, and the quantity it
     * moved. It is what a reversal has to name to free exactly the rows the sale consumed.
     */
    private record OpenSale(UUID ledgerMovementId, BigDecimal quantity) {}

    /**
     * The key every ledger row needs, plus the identity of the row that states the fact.
     *
     * <p>{@code sourceType} and {@code writeSeq} are both required, and every call site in this class states them,
     * because nothing is implied by the writer and the identity of every movement has to be readable where the
     * fact is booked. The sources are all the document's own: a receipt line states
     * {@link StockLedgerWriter#RECEIPT_LINE_SOURCE_TYPE} and the {@code goods_receipt_items} id, a sale and its
     * reversal state the sales-order line and the line's {@code writeSeq} ({@code V73}), and a manual per-store
     * edit states {@link StockLedgerWriter#MANUAL_ADJUSTMENT_SOURCE_TYPE} and the movement's own id, because no
     * document states a manual edit (D-2).</p>
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
     * Books an inbound movement (a receipt or the increase side of a manual edit), which is the only write: the
     * projections follow from it by trigger.
     *
     * <p>The cost is what {@code product_variant_store_stock.cost_price} records for this variant in this store —
     * the only cost this deployment keeps, and the same source {@code V62}'s backfill used. A missing value is
     * booked as zero with the reason stated on the row, never refused: a receipt of stock that has no recorded cost
     * is still a receipt.</p>
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
     * <p><b>Read by the source the movements carry, which is what the per-write identity bought.</b> A sale books
     * {@code source_type}/{@code source_id} = the line's own reference and the line id, so the ledger alone
     * answers "what does this line still hold open": one row per write and location, each with the quantity it
     * moved. That is a strictly larger set than the mirror could express — a line sold, grown and sold again holds
     * two open rows at one location — and it is the set a reversal has to name, because {@code V61} demands a
     * reversal move exactly the quantity of the row it names.</p>
     */
    private Map<SaleBalanceKey, List<OpenSale>> openLedgerSales(String referenceType, UUID referenceId) {
        var open = new LinkedHashMap<SaleBalanceKey, List<OpenSale>>();
        for (var sale : stockLedgerWriter.openSales(referenceType, referenceId)) {
            open.computeIfAbsent(
                            new SaleBalanceKey(sale.productVariantId(), sale.companyStoreId(), sale.storeLocationId()),
                            key -> new ArrayList<>())
                    .add(new OpenSale(sale.movementId(), orZero(sale.quantity())));
        }
        return open;
    }

    private BigDecimal storedCostPrice(UUID productVariantId, UUID companyStoreId) {
        return productVariantStoreStockRepository
                .findByProductVariantIdAndCompanyStoreId(productVariantId, companyStoreId)
                .map(storeStock -> storeStock.getCostPrice())
                .orElse(null);
    }

    /** NULL stock only means "never set" — a row read back before its default is materialized. */
    private static BigDecimal orZero(BigDecimal stock) {
        return stock != null ? stock : BigDecimal.ZERO;
    }

    /**
     * The company that owns a store, read from the store itself.
     *
     * <p>This engine deliberately holds no {@code CurrentUserContext}: a stock movement is the document's business,
     * and the document already verified the caller's grant on its store. What the argument does carry is the
     * tenant, and this is the one place it is derived from ({@link CompanyStoreOwnerResolver}) so that a movement
     * and every store-scoped read agree on which company a store belongs to.</p>
     *
     * @throws CompanyStoreNotFoundException when the store does not exist
     */
    private UUID companyIdOf(UUID companyStoreId) {
        var store = companyStoreRepository
                .findById(companyStoreId)
                .orElseThrow(() -> new CompanyStoreNotFoundException(companyStoreId));
        return companyStoreOwnerResolver.companyIdOf(store);
    }
}
