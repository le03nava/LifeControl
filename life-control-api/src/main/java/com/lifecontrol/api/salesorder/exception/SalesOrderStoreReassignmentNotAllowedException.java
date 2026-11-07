package com.lifecontrol.api.salesorder.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * Raised when a request tries to move a sales order to another company store while the order still
 * holds active items.
 *
 * <p>It exists because the stock is booked at the store the order names when it is charged, and every reversal
 * credits {@code so.getCompanyStoreId()}. Re-attributing an order between the two moments would leave the store
 * that was charged understated and credit one that never sold the goods — and since ADR-0002 decision 7 nothing is
 * booked before the charge, so the refusal is now stricter than that invariant strictly requires: the charge would
 * simply book at the new store. Narrowing it is recorded as a follow-up in {@code odd/tasks/money-first-class.md}
 * (T6b log).</p>
 */
public class SalesOrderStoreReassignmentNotAllowedException extends ConflictException {

    private final UUID salesOrderId;
    private final UUID currentCompanyStoreId;
    private final UUID requestedCompanyStoreId;

    public SalesOrderStoreReassignmentNotAllowedException(
            UUID salesOrderId, UUID currentCompanyStoreId, UUID requestedCompanyStoreId) {
        super("Sales order %s cannot be reassigned from company store %s to %s while it has active items"
                .formatted(salesOrderId, currentCompanyStoreId, requestedCompanyStoreId));
        this.salesOrderId = salesOrderId;
        this.currentCompanyStoreId = currentCompanyStoreId;
        this.requestedCompanyStoreId = requestedCompanyStoreId;
    }

    public UUID getSalesOrderId() {
        return salesOrderId;
    }

    public UUID getCurrentCompanyStoreId() {
        return currentCompanyStoreId;
    }

    public UUID getRequestedCompanyStoreId() {
        return requestedCompanyStoreId;
    }
}
