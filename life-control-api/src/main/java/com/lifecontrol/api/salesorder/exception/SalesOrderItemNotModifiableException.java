package com.lifecontrol.api.salesorder.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * Raised when a request tries to modify a sales-order line the charge will never book: a line whose status is
 * the terminal {@code Cancelled}, or a soft-deleted line ({@code enabled = false}).
 *
 * <p>Both are lines outside the set the charge walks — it books enabled, non-cancelled ones — so an item-level
 * update would edit a line whose stock nobody will ever move, and nothing automatic would reconcile it: the
 * order-level {@code PUT} is the surface that deals in the whole line set, and it re-enables the line before the
 * charge can see it again (W3-D11). A cancelled line is terminal, so reviving it is not on offer at all.</p>
 */
public class SalesOrderItemNotModifiableException extends ConflictException {

    public SalesOrderItemNotModifiableException(UUID itemId, String state) {
        super("Sales order item " + itemId + " is " + state + " and cannot be modified");
    }
}
