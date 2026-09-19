package com.lifecontrol.api.inventory.service;

import com.lifecontrol.api.inventory.model.InventoryMovement;
import com.lifecontrol.api.inventory.model.MovementType;
import com.lifecontrol.api.inventory.model.ProductVariantLocation;
import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import com.lifecontrol.api.inventory.repository.ProductVariantLocationRepository;
import com.lifecontrol.api.product.exception.ProductVariantNotFoundException;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import java.math.BigDecimal;
import java.util.Objects;
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
 * <h2>Interim contract (until the sales rework, workstream W3, lands)</h2>
 * <ol>
 *   <li><b>Sales still bypasses locations.</b> {@code SalesOrderService.applyStockChanges} keeps
 *       subtracting the sold quantity from {@code product_variants.stock} with no location
 *       dimension, because W3 is deferred.</li>
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
    private final ProductVariantLocationRepository productVariantLocationRepository;
    private final InventoryMovementRepository inventoryMovementRepository;

    public InventoryService(
            ProductVariantRepository productVariantRepository,
            ProductVariantLocationRepository productVariantLocationRepository,
            InventoryMovementRepository inventoryMovementRepository) {
        this.productVariantRepository = productVariantRepository;
        this.productVariantLocationRepository = productVariantLocationRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
    }

    /**
     * Adds {@code quantity} of {@code productVariantId} to the stock held at {@code storeLocationId}
     * and to the sellable aggregate, appending one {@code RECEIPT} movement to the ledger.
     *
     * @param quantity signed amount received; must be greater than zero
     * @param referenceType free-form source discriminator (the goods receipt in W2c)
     * @param referenceId identity of that source, kept so the ledger can be traced back to it
     * @param createdBy username of the receiver, when known
     * @throws IllegalArgumentException when {@code quantity} is not greater than zero, or when the
     *     variant does not belong to {@code companyStoreId}
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
        //    lock a variant, create a balance row or leave a ledger entry behind.
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Receipt quantity must be greater than zero, but was " + quantity);
        }

        // 2. Lock the variant first, always. Every receipt for this variant takes this row, and a
        //    balance row belongs to exactly one variant, so the single global order
        //    variant -> balance is provably cycle-free: two receipts that share a variant serialize
        //    here, and two that share a balance also share that balance's variant. Nothing is read
        //    before this lock, so no probe can pick a different order.
        //    SalesOrderService.applyStockChanges (SalesOrderService.java:803-813) sorts the ids of
        //    the MULTIPLE variants it locks for the same reason; a receipt locks exactly one variant
        //    and one balance, so this fixed order replaces that sorted choice.
        var variant = lockVariant(productVariantId);

        // 3. The variant and the store must agree, otherwise the movement below would attribute the
        //    receipt to a store this variant does not belong to. Nothing has been written yet.
        if (!Objects.equals(variant.getCompanyStoreId(), companyStoreId)) {
            throw new IllegalArgumentException("Product variant " + productVariantId + " belongs to store "
                    + variant.getCompanyStoreId() + ", not to the supplied store " + companyStoreId);
        }

        // 4. Only now acquire the balance. The conflict-tolerant insert creates the row when it is
        //    missing; it is no longer required for cycle safety under variant-first ordering, but it
        //    stays a cheap defence and spares any future caller that skips the lock from the
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

        // 6. ADDITIVE, NEVER A RECOMPUTE. `product_variants.stock` is incremented here and must
        //    never be re-derived as SUM(product_variant_locations.stock). Until W3 makes the sales
        //    path location-aware, SalesOrderService.applyStockChanges (SalesOrderService.java:861,
        //    :865) still subtracts the sold quantity from `product_variants.stock` without touching
        //    any location row, so the aggregate is legitimately BELOW the sum of the location
        //    balances: aggregate = SUM(locations) - everything sold since receipt. Recomputing the
        //    aggregate from the location sum would silently resurrect stock that sales already sold.
        //    W3 owes the reconciliation and the switch of this method to recompute semantics.
        // A NULL stock means the column was never set (its default is 0), so it counts as zero.
        location.setStock(orZero(location.getStock()).add(quantity));
        productVariantLocationRepository.save(location);

        variant.setStock(orZero(variant.getStock()).add(quantity));
        productVariantRepository.save(variant);
    }

    /** NULL stock only means "never set" — {@code product_variants.stock} has no NOT NULL constraint. */
    private static BigDecimal orZero(BigDecimal stock) {
        return stock != null ? stock : BigDecimal.ZERO;
    }

    private ProductVariant lockVariant(UUID productVariantId) {
        return productVariantRepository
                .findByIdForUpdate(productVariantId)
                .orElseThrow(() -> new ProductVariantNotFoundException(productVariantId));
    }

    private ProductVariantLocation lockBalance(UUID productVariantId, UUID storeLocationId) {
        return productVariantLocationRepository
                .findByProductVariantIdAndStoreLocationIdForUpdate(productVariantId, storeLocationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Product variant location balance not found for variant " + productVariantId
                                + " and store location " + storeLocationId + " after it was locked or created"));
    }
}
