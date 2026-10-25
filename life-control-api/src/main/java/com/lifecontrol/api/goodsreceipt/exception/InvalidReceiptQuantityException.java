package com.lifecontrol.api.goodsreceipt.exception;

import java.math.BigDecimal;

/**
 * {@code 400} when a received quantity is not strictly positive or carries more than two decimals.
 *
 * <p>The purchase order line's quantity and its received quantity are {@code DECIMAL(12,2)}, so a
 * fractional quantity is valid and the decimal-place check lives at the receipt boundary instead: a
 * third decimal would be rounded silently by the column. Both quantity-shape rules share this type;
 * the message names the offending line and the exact rule.</p>
 */
public class InvalidReceiptQuantityException extends GoodsReceiptValidationException {

    public InvalidReceiptQuantityException(int lineIndex, String reason, BigDecimal quantityReceived) {
        super("Reception line " + lineIndex + " has an invalid quantityReceived " + quantityReceived + ": " + reason);
    }
}
