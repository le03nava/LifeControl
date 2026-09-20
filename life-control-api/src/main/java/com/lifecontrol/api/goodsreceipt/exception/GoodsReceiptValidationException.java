package com.lifecontrol.api.goodsreceipt.exception;

/**
 * Base class of every goods-receipt validation failure that must answer {@code 400}.
 *
 * <p>It extends {@link IllegalArgumentException} because that is the category
 * {@code GlobalExceptionHandler} already maps to {@code 400}; extending it makes every receipt
 * validation failure inherit the mapping by category instead of requiring an entry in the handler.
 * The alternative used by {@code InvalidSalesOrderChargeException} — a plain {@code RuntimeException}
 * plus an explicit handler entry — is not available here, because the receipt work unit must not
 * touch the shared handler.</p>
 */
public class GoodsReceiptValidationException extends IllegalArgumentException {

    public GoodsReceiptValidationException(String message) {
        super(message);
    }

    /**
     * Variant that keeps the originating cause, for rules the receipt service delegates to another
     * service (its typed exception is translated into a line-named receipt error, not swallowed).
     */
    public GoodsReceiptValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
