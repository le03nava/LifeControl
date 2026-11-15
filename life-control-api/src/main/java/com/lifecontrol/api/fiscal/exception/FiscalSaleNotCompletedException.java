package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * The sale exists but has not reached {@code COMPLETED}, so there is nothing to invoice yet ({@code S11},
 * {@code F6} of {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why a conflict and not a 400.</b> The request names a sale that is real; what fails is the state of the
 * server's own data at the moment it is asked. The same family the sale's own terminal-state guards use
 * ({@code SalesOrderAlreadyFinalizedException}), so a caller that retries after completing the sale is doing
 * the right thing and one that retries without completing it gets the same named refusal.</p>
 *
 * <p><b>Why the state is named and the sale too.</b> "Not completed" alone leaves an operator looking for the
 * sale; the pair is what makes the refusal actionable without a second query.</p>
 */
public class FiscalSaleNotCompletedException extends ConflictException {

    public FiscalSaleNotCompletedException(UUID salesOrderId, String status) {
        super("Sales order " + salesOrderId + " is " + status + " and a fiscal document derives only from a "
                + "COMPLETED sale: an order still being edited, charged or cancelled has no final amount and no "
                + "final line set to state.");
    }
}
