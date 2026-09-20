package com.lifecontrol.api.goodsreceipt.exception;

import java.util.UUID;

/**
 * {@code 400} when a received line references a product variant that does not belong to the purchase
 * order's store.
 *
 * <p>This is checked before {@code InventoryService.applyReceipt} runs, so the operator never sees
 * the mutator's bare {@link IllegalArgumentException} (W2b debt #4); the failure is typed and names
 * the offending line.</p>
 */
public class PurchaseOrderVariantNotInStoreException extends GoodsReceiptValidationException {

    public PurchaseOrderVariantNotInStoreException(int lineIndex, UUID purchaseOrderDetailId, UUID productVariantId) {
        super("Reception line " + lineIndex + " (purchase order detail " + purchaseOrderDetailId
                + ") references product variant " + productVariantId
                + " which does not belong to the purchase order's store");
    }
}
