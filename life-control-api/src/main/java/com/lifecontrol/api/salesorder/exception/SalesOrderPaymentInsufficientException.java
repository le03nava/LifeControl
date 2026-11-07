package com.lifecontrol.api.salesorder.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown when a document would be charged while its non-voided payments do not cover its total
 * (ADR-0002 invariant M3: a document transitions to {@code Completed} only when
 * {@code Σ CHARGE − Σ REFUND ≥ total_amount}).
 *
 * <p>Thin subclass of {@link ConflictException}: the {@code GlobalExceptionHandler} maps the parent to 409
 * and needs no method of its own for this type. The refusal is a business one and not a bad request — the
 * request is well formed, the document simply cannot transition — and it names both numbers, because a
 * shortfall an operator cannot see is the same as no message at all.</p>
 */
public class SalesOrderPaymentInsufficientException extends ConflictException {

    public SalesOrderPaymentInsufficientException(UUID orderId, BigDecimal paid, BigDecimal total) {
        super("Cannot charge sales order " + orderId + ": its non-voided payments add up to " + paid.toPlainString()
                + ", which does not cover the order's total of " + total.toPlainString()
                + ". The document transitions to Completed only when its charges, minus its refunds, cover the"
                + " total.");
    }
}
