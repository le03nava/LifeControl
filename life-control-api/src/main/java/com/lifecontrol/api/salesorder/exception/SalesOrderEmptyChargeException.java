package com.lifecontrol.api.salesorder.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown when a document would be charged while its enabled lines add up to zero, so there is no payment to
 * append (ADR-0002 decision 5: a charge is a {@code sale_payment} row that states what the customer handed
 * over).
 *
 * <p>Thin subclass of {@link ConflictException}: the {@code GlobalExceptionHandler} maps the parent to 409
 * and needs no method of its own for this type. The refusal is a business one and not a bad request — the
 * request is well formed, the document simply has nothing to pay for — and it names the order and its total,
 * because a customer waiting at the counter needs to know which document stopped the charge.</p>
 *
 * <p><b>Why the refusal lives before the insert and not in the schema.</b> {@code sale_payment} does hold
 * {@code CHECK (amount > 0)} ({@code V83}), so a zero payment is refused either way — but the schema's refusal
 * arrives at flush as a {@code DataIntegrityViolationException}, which the handler answers with a generic 409
 * that names neither the order nor the reason, and which reads like a race with another writer when it is in
 * fact a document with nothing in it. A business rule stated in SQL alone cannot explain itself on the way
 * out. The constrained insert stays as the last line of defence against a future writer; this guard is what
 * makes the refusal legible on the one path that can produce it today.</p>
 */
public class SalesOrderEmptyChargeException extends ConflictException {

    public SalesOrderEmptyChargeException(UUID orderId, BigDecimal total) {
        super("Cannot charge sales order " + orderId + ": its enabled lines add up to " + total.toPlainString()
                + ", so there is nothing to pay. A document with nothing to pay cannot be charged — there is no"
                + " payment to append — and sale_payment refuses a zero amount.");
    }
}
