package com.lifecontrol.api.goodsreceipt.exception;

import java.math.BigDecimal;

/**
 * {@code 400} when a received quantity is not strictly positive or is not a whole number.
 *
 * <p>The purchase order's quantities are {@code INTEGER}, so the integrality check lives at the
 * receipt boundary (W2-D7) instead of changing the purchase-order schema. Both quantity-shape rules
 * share this type; the message names the offending line and the exact rule.</p>
 */
public class InvalidReceiptQuantityException extends GoodsReceiptValidationException {

    public InvalidReceiptQuantityException(int lineIndex, String reason, BigDecimal quantityReceived) {
        super("Reception line " + lineIndex + " has an invalid quantityReceived " + quantityReceived + ": " + reason);
    }
}
