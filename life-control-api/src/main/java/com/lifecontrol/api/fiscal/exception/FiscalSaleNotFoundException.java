package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * The sale a document would derive from is not there — either it does not exist or it belongs to another
 * company ({@code S11} of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why the two cases are one refusal and one sentence.</b> {@code sales_orders} is armed since {@code V65}
 * and {@code FORCE}d, so a sale of another tenant is not "forbidden" to this caller — it is <b>not there</b>,
 * and the policy answers no row. Splitting the message would mean asking the database a question the policy
 * has already answered, and it would tell a caller that a sale exists in another tenant, which is the
 * cross-tenant existence leak ADR-0005 refuses. The message therefore names the id and nothing about tenancy.
 */
public class FiscalSaleNotFoundException extends ResourceNotFoundException {

    public FiscalSaleNotFoundException(UUID salesOrderId) {
        super("Sales order not found: " + salesOrderId + ". A fiscal document derives from a completed sale of "
                + "the caller's own company, and a sale of another company is not visible to this one.");
    }
}
