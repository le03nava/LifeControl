package com.lifecontrol.api.salesorder.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * Raised when a request tries to modify a sales-order line that no longer holds stock: a line whose
 * status is the terminal {@code Cancelled}, or a soft-deleted line ({@code enabled = false}).
 *
 * <p>Both states mean the line's ledger reference is fully reversed, so an item-level update could
 * not reconcile stock correctly. A cancelled line was given back in full, so selling it again would
 * write a {@code SALE} row the rule says no line holds. A soft-deleted line is never re-enabled by
 * the item-level endpoint, so its deduction would be stranded: every order-level restoration walks
 * the enabled lines only, and nothing automatic would ever reverse it. The supported route to revive
 * such a line is the order-level {@code PUT}, which deals in the whole line set and re-enables the
 * line before it is sold again (W3-D11).</p>
 */
public class SalesOrderItemNotModifiableException extends ConflictException {

    public SalesOrderItemNotModifiableException(UUID itemId, String state) {
        super("Sales order item " + itemId + " is " + state + " and cannot be modified");
    }
}
