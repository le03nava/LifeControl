package com.lifecontrol.api.salesorder.service;

import com.lifecontrol.api.common.state.DocumentState;
import com.lifecontrol.api.common.state.StatusSubject;
import com.lifecontrol.api.salesorder.model.SalesOrderItemState;
import com.lifecontrol.api.salesorder.model.SalesOrderState;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.repository.StatusRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@code status_id} a request carries to the sales family's typed state, through the catalog's
 * {@code code} (ADR-0004 decision 7): the catalog stopped being the authority and became the label of a
 * code, so the only thing a request can name is the code's row, and the only thing the service may decide a
 * move with is the constant that code spells.
 *
 * <p><b>No name is compared, anywhere.</b> Renaming {@code Draft} in the catalog changes what the screen
 * says and nothing else (ADR-0004, {@code ## Consequences}); a comparison against {@code "Draft"} would
 * quietly turn that rename into a 409, which is the coupling decisions 1 and 7 exist to remove. The label is
 * read here for one purpose only: naming the row in a refusal, because the caller sent an id and has no
 * other way to know which row it was.</p>
 *
 * <p><b>The read is the model's, not a raw query.</b> {@link StatusRepository#findWithStatusTypeById} brings
 * the row and its family in one fetch, so this class owns the mapping and the refusal and nothing about how
 * a catalog row is loaded. That is also why it shares the caller's transaction: the row is read on the same
 * connection and inside the same snapshot as the write it guards.</p>
 *
 * <p><b>Three refusals, and each one names what it saw.</b> A row of another family is the client's mistake
 * and answers {@code 400}, the same way {@code StatusValidator} refused a status of the wrong type before
 * this slice; a row with no code is an out-of-scope family or a catalog row created at run time, and there is
 * no constant to move to; a code the family's enum does not declare is a state the compiler has never heard
 * of. The last two are refused instead of guessed: moving a document to "whatever the label looked like"
 * would be the string comparison ADR-0004 removes.</p>
 */
@Component
public class SalesOrderStatusCatalog {

    private final StatusRepository statusRepository;

    public SalesOrderStatusCatalog(StatusRepository statusRepository) {
        this.statusRepository = statusRepository;
    }

    /**
     * The state a sales order's {@code status_id} names.
     *
     * @throws StatusNotFoundException when no catalog row carries that id
     * @throws IllegalArgumentException when the row belongs to another family, carries no code, or carries a
     *     code {@link SalesOrderState} does not declare
     */
    public SalesOrderState requireOrderState(UUID statusId) {
        return requireState(statusId, StatusSubject.SALES_ORDER, SalesOrderState.class);
    }

    /**
     * The state a sales-order line's {@code status_id} names.
     *
     * @throws StatusNotFoundException when no catalog row carries that id
     * @throws IllegalArgumentException when the row belongs to another family, carries no code, or carries a
     *     code {@link SalesOrderItemState} does not declare
     */
    public SalesOrderItemState requireItemState(UUID statusId) {
        return requireState(statusId, StatusSubject.SALES_ORDER_ITEM, SalesOrderItemState.class);
    }

    private <S extends Enum<S> & DocumentState<S>> S requireState(
            UUID statusId, StatusSubject subject, Class<S> family) {
        if (statusId == null) {
            throw new IllegalArgumentException("A status id is required to name a state of " + subject);
        }

        var status = statusRepository
                .findWithStatusTypeById(statusId)
                .orElseThrow(() -> new StatusNotFoundException(statusId));
        var catalogName = status.getStatusName();
        var typeName = status.getStatusType().getStatusTypeName();

        // The family first: a row of another family answers with its label in a message about the type,
        // which is the one thing the caller can fix ("the id I sent is a line's status").
        if (!subject.name().equalsIgnoreCase(typeName)) {
            throw new IllegalArgumentException("The provided status does not belong to type " + subject.name()
                    + ": catalog status '" + catalogName + "' (" + statusId + ") belongs to " + typeName);
        }

        var code = status.getCode();
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Catalog status '" + catalogName + "' (" + statusId
                    + ") carries no code, and " + subject + " has no state a label can name. ADR-0004 decision 7"
                    + " makes the code the only thing the machine may read");
        }

        try {
            return Enum.valueOf(family, code);
        } catch (IllegalArgumentException notDeclared) {
            throw new IllegalArgumentException(
                    "Catalog status '" + catalogName + "' (" + statusId + ") carries code '" + code + "', which "
                            + family.getSimpleName() + " does not declare",
                    notDeclared);
        }
    }
}
