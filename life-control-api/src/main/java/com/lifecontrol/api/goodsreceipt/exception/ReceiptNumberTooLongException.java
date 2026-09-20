package com.lifecontrol.api.goodsreceipt.exception;

/**
 * Fail-closed guard for the {@code receipt_number VARCHAR(30)} column.
 *
 * <p>The generator throws instead of truncating when the composed number would not fit: a truncated
 * receipt number would be a silent, unrecoverable corruption of an immutable document. This is an
 * internal invariant (the order number is server-generated and bounded), so it maps to {@code 500}
 * through the handler's {@code Exception} fallback rather than to a {@code 4xx}.</p>
 */
public class ReceiptNumberTooLongException extends IllegalStateException {

    public ReceiptNumberTooLongException(String receiptNumber, String orderNumber, int maxLength) {
        super("Generated receipt number '" + receiptNumber + "' for order " + orderNumber
                + " exceeds the maximum length of " + maxLength + " characters");
    }
}
