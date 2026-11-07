package com.lifecontrol.api.salesorder.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown when a charge's payments do not add up to the document's total (ADR-0002 invariant M3, read exactly).
 *
 * <p><b>Why exact equality and not "at least".</b> Each payment's {@code amount} is what that payment applies to
 * the sale, so a sum below the total leaves the document unpaid and a sum above it records money the document does
 * not cost — a row nobody can reconcile against the sale it names. The tender is the other number: what the
 * customer handed over lives in {@code tendered_amount}, and a 200 sale settled with a 500 note is 200 of
 * {@code amount} and 500 of tender, never 500 of {@code amount}.</p>
 *
 * <p><b>Why this reads the request and {@code netPaid} still runs after the inserts.</b> This guard judges what was
 * asked for, so a request that could never balance is refused before any row exists. The table-reading guard that
 * follows the inserts judges what actually landed — including anything a previous request left and any void row —
 * and that half stays because a sum over rows is the only place M3 can be stated about the document.</p>
 *
 * <p>Thin subclass of {@link ConflictException}: the {@code GlobalExceptionHandler} maps the parent to 409 and
 * needs no method of its own. The refusal is a business one and not a bad request — every payment in it is well
 * formed on its own — and it names the sum and the total, because a difference an operator cannot see is the same
 * as no message at all.</p>
 */
public class SalesOrderPaymentTotalMismatchException extends ConflictException {

    public SalesOrderPaymentTotalMismatchException(UUID orderId, BigDecimal statedTotal, BigDecimal documentTotal) {
        super("Cannot charge sales order " + orderId + ": its payments add up to " + statedTotal.toPlainString()
                + ", and the document's total is " + documentTotal.toPlainString()
                + ". A charge's payments apply to the sale, so they add up to the document's total exactly — a sum"
                + " below it leaves the document unpaid, and a sum above it would record money the document does"
                + " not cost (ADR-0002 M3). What the customer handed over is the payment's tender, not its amount.");
    }
}
