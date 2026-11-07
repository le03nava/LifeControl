package com.lifecontrol.api.salesorder.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown when a payment's tender and change do not hold the arithmetic that makes them a tender and a change
 * (ADR-0002 invariant M4, {@code ck_payment_tender}).
 *
 * <p><b>Why this is a {@code 400} and not the constraint's {@code 409}.</b> {@code ck_payment_tender} states the
 * same rule, so a service that did not judge it would still refuse the request — at flush, as a
 * {@code DataIntegrityViolationException} that the handler answers with one generic sentence and that is
 * indistinguishable from a stock conflict on the same endpoint. The request is what is wrong here, so the refusal
 * is a bad request that names the payment and the number; the constraint keeps its job as the last line of
 * defence and is not the answer a client meets. That is why this extends
 * {@link InvalidSalesOrderChargeException}, whose {@code 400} the handler already states, rather than the
 * {@code ConflictException} family the other two charge guards use.</p>
 *
 * <p>The three shapes it can answer are exactly the constraint's own: a change that is not the tender minus the
 * amount, half a pair stated on its own, and a tender below the amount it is supposed to have covered.</p>
 */
public class SalesOrderTenderInconsistentException extends InvalidSalesOrderChargeException {

    private SalesOrderTenderInconsistentException(String message) {
        super(message);
    }

    /** {@code change = tendered - amount} did not hold: the numbers are stated and they disagree. */
    public static SalesOrderTenderInconsistentException changeIsNotTheDifference(
            UUID orderId,
            String paymentPosition,
            String method,
            BigDecimal amount,
            BigDecimal tenderedAmount,
            BigDecimal changeAmount) {
        var expected = tenderedAmount.subtract(amount);
        return new SalesOrderTenderInconsistentException(sentence(
                orderId,
                paymentPosition,
                method,
                "states a tender of " + tenderedAmount.toPlainString() + " and a change of "
                        + changeAmount.toPlainString() + ", but the change of a cash payment is tendered - amount = "
                        + expected.toPlainString() + " (the payment's amount is " + amount.toPlainString() + ")."));
    }

    /** Half a pair: no change with the tender, or a change with no tender to come back from. */
    public static SalesOrderTenderInconsistentException halfAPair(
            UUID orderId, String paymentPosition, String method, BigDecimal tendered, BigDecimal change) {
        var stated = tendered == null
                ? "a change of " + change.toPlainString() + " and no tender"
                : "a tender of " + tendered.toPlainString() + " and no change";
        return new SalesOrderTenderInconsistentException(sentence(
                orderId,
                paymentPosition,
                method,
                "states " + stated + ". A tender and a change are stated together or not at all: the pair says how"
                        + " much cash was counted and how much went back, and half of it says neither."));
    }

    /** Less handed over than the payment applies to the sale, which is not a tender at all. */
    public static SalesOrderTenderInconsistentException tenderBelowAmount(
            UUID orderId, String paymentPosition, String method, BigDecimal amount, BigDecimal tenderedAmount) {
        return new SalesOrderTenderInconsistentException(sentence(
                orderId,
                paymentPosition,
                method,
                "states a tender of " + tenderedAmount.toPlainString() + " for an amount of "
                        + amount.toPlainString()
                        + ". The tender is what the customer handed over and cannot be less than what the payment"
                        + " applies to the sale."));
    }

    private static String sentence(UUID orderId, String paymentPosition, String method, String reason) {
        return "Cannot charge sales order " + orderId + ": payment " + paymentPosition + " through payment method "
                + method + " " + reason + " ck_payment_tender holds the same arithmetic; the request is refused"
                + " here, before any row is written, so the refusal names the payment instead of the database.";
    }
}
