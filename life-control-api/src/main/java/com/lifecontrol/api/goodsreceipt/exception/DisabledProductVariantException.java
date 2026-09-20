package com.lifecontrol.api.goodsreceipt.exception;

import java.util.UUID;

/**
 * {@code 400} when a received line references a product variant that is soft-deleted ({@code
 * enabled = false}).
 *
 * <p>This rule exists because {@code InventoryService#applyReceipt} resolves the variant through
 * {@code ProductVariantRepository#findByIdForUpdate}, which filters {@code enabled = true}: a variant
 * disabled after the purchase order was raised would pass the whole validation phase, the receipt
 * row would be written, and only then the mutator would throw
 * {@code ProductVariantNotFoundException} (404). Checking it here turns that into a precise,
 * line-named 400 before the first write.</p>
 *
 * <p>A {@code NULL} variant and a variant that belongs to another store keep their own diagnostics
 * and are checked first, so the more specific cause always wins.</p>
 */
public class DisabledProductVariantException extends GoodsReceiptValidationException {

    public DisabledProductVariantException(int lineIndex, UUID purchaseOrderDetailId, UUID productVariantId) {
        super("Reception line " + lineIndex + " (purchase order detail " + purchaseOrderDetailId
                + ") references product variant " + productVariantId
                + " which is disabled and cannot receive goods");
    }
}
