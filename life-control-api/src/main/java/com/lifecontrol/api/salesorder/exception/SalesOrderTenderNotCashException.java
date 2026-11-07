package com.lifecontrol.api.salesorder.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown when a payment that is not cash states a tender (ADR-0002 invariant M4).
 *
 * <p><b>Why the schema cannot say this.</b> A {@code CHECK} cannot read another table, so "change is returned only
 * for a cash method" is not admissible inside {@code ck_payment_tender} — that constraint only holds the
 * arithmetic, and a card payment with a tender and a consistent change passes it. {@code payment_methods.is_cash}
 * is the fact, this guard is its first reader in {@code salesorder}, and the guard is what makes the invariant
 * true rather than aspirational.</p>
 *
 * <p>Thin subclass of {@link ConflictException}: a domain refusal, in the same family as the two guards beside it,
 * and not a malformed request. It names the method — its id <b>and</b> its name — because the amount and the
 * arithmetic are both correct in the request this refuses, so a message that named the money would send an
 * operator to recount a drawer that is already right.</p>
 */
public class SalesOrderTenderNotCashException extends ConflictException {

    public SalesOrderTenderNotCashException(
            UUID orderId,
            String paymentPosition,
            BigDecimal tenderedAmount,
            BigDecimal changeAmount,
            UUID paymentMethodId,
            String paymentMethodName) {
        super("Cannot charge sales order " + orderId + ": payment " + paymentPosition + " states a tender of "
                + tenderedAmount.toPlainString() + " and a change of " + changeAmount.toPlainString()
                + " through payment method '" + paymentMethodName + "' (" + paymentMethodId
                + "), which is not a cash method. Change is returned only for a cash method (ADR-0002 M4): every"
                + " other method is charged for its amount and counts no cash, so its tender and change are absent.");
    }
}
