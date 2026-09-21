package com.lifecontrol.api.salesorder.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * Raised when a request tries to move a sales order to another company store while the order still
 * holds active items.
 *
 * <p>It exists because stock lives on the {@code (variant, store)} row of the order's store, and
 * every restoration path credits {@code so.getCompanyStoreId()}. Re-attributing an order that
 * already deducted stock therefore left the original store permanently understated and credited a
 * store that never received the goods. A store change with no active items moves no stock and stays
 * allowed.</p>
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
