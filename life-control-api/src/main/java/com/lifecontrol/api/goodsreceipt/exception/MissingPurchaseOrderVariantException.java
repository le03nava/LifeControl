package com.lifecontrol.api.goodsreceipt.exception;

import java.util.UUID;

/**
 * {@code 400} when a received purchase-order line has no product variant (the legacy {@code NULL}
 * variant W1b tolerated on existing rows).
 *
 * <p>The line must never be silently skipped: {@code goods_receipt_items.product_variant_id} is
 * {@code NOT NULL} and the line's inventory effect would be lost. Failing closed names the offending
 * line and reason instead.</p>
 */
public class MissingPurchaseOrderVariantException extends GoodsReceiptValidationException {

    public MissingPurchaseOrderVariantException(int lineIndex, UUID purchaseOrderDetailId) {
        super("Reception line " + lineIndex + " (purchase order detail " + purchaseOrderDetailId
                + ") has no product variant; the line cannot be received because its inventory effect would be lost");
    }
}
