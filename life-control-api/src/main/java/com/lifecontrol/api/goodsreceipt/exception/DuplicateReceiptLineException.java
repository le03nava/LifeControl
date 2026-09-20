package com.lifecontrol.api.goodsreceipt.exception;

import java.util.UUID;

/**
 * {@code 400} when one request receives the same purchase-order line twice.
 *
 * <p>A repeated line would apply two inventory movements and two accumulated-quantity writes for one
 * detail, so it is rejected before any write.</p>
 */
public class DuplicateReceiptLineException extends GoodsReceiptValidationException {

    public DuplicateReceiptLineException(int lineIndex, UUID purchaseOrderDetailId) {
        super("Reception line " + lineIndex + " repeats purchase order detail " + purchaseOrderDetailId
                + "; each line may be received at most once per request");
    }
}
