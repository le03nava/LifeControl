package com.lifecontrol.api.inventory.service;

import com.lifecontrol.api.inventory.model.InventoryMovement;
import com.lifecontrol.api.inventory.model.MovementType;
import com.lifecontrol.api.inventory.model.ProductVariantLocation;
import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import com.lifecontrol.api.inventory.repository.ProductVariantLocationRepository;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.product.repository.ProductVariantStoreStockRepository;
import java.math.BigDecimal;
import java.util.UUID;
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

    private final ProductVariantRepository productVariantRepository;
    private final ProductVariantStoreStockRepository productVariantStoreStockRepository;
    private final ProductVariantLocationRepository productVariantLocationRepository;
    private final InventoryMovementRepository inventoryMovementRepository;

    public InventoryService(
            ProductVariantRepository productVariantRepository,
            ProductVariantStoreStockRepository productVariantStoreStockRepository,
            ProductVariantLocationRepository productVariantLocationRepository,
            InventoryMovementRepository inventoryMovementRepository) {
        this.productVariantRepository = productVariantRepository;
        this.productVariantStoreStockRepository = productVariantStoreStockRepository;
        this.productVariantLocationRepository = productVariantLocationRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
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
