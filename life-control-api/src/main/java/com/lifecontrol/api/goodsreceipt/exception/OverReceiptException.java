package com.lifecontrol.api.goodsreceipt.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code 400} when a received line would push the accumulated reception past the ordered quantity.
 *
 * <p>The accumulated quantity is {@code alreadyReceived + thisReception}; the message names the
 * offending line and both quantities.</p>
 */
public class OverReceiptException extends GoodsReceiptValidationException {

    public OverReceiptException(int lineIndex, UUID purchaseOrderDetailId, BigDecimal accumulated, BigDecimal ordered) {
        super("Reception line " + lineIndex + " (purchase order detail " + purchaseOrderDetailId
                + ") would over-receive: accumulated " + accumulated + " exceeds the ordered quantity " + ordered);
    }
}
