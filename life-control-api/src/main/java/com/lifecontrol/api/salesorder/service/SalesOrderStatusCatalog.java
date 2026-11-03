package com.lifecontrol.api.salesorder.service;

import com.lifecontrol.api.common.state.DocumentStateCatalog;
import com.lifecontrol.api.common.state.StatusSubject;
import com.lifecontrol.api.salesorder.model.SalesOrderItemState;
import com.lifecontrol.api.salesorder.model.SalesOrderState;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The sales family's two names for {@link DocumentStateCatalog} (ADR-0004 decision 7): the resolution and its
 * four refusals live in that shared class, because the purchase family needed the same answers and would
 * otherwise have carried a copy of them.
 *
 * <p>What is left here is the vocabulary this module and its controller are written against — an order's
 * state and a line's state — so a call site reads as the document it is about instead of repeating the family
 * on every line. There is no logic below: a delegate that grows a branch is the copy this class exists to
 * avoid.</p>
 *
 * <p><b>The id it takes is the request's, and it is the only direction that still needs a catalog row.</b>
 * ADR-0004 step 9 dropped {@code status_id} from the five document tables, so the row a document is
 * <em>labelled</em> by is a projection of the code and no longer stored; what a request can name is still a
 * catalog row, and this is the one place that id becomes a constant.</p>
 */
@Component
public class SalesOrderStatusCatalog {

    private final DocumentStateCatalog documentStateCatalog;

    public SalesOrderStatusCatalog(DocumentStateCatalog documentStateCatalog) {
        this.documentStateCatalog = documentStateCatalog;
    }

    /**
     * The state a sales order's requested {@code statusId} names.
     *
     * @throws StatusNotFoundException when no catalog row carries that id
     * @throws IllegalArgumentException when the row belongs to another family, carries no code, or carries a
     *     code {@link SalesOrderState} does not declare
     */
    public SalesOrderState requireOrderState(UUID statusId) {
        return documentStateCatalog.requireState(StatusSubject.SALES_ORDER, statusId, SalesOrderState.class);
    }

    /**
     * The state a sales-order line's requested {@code statusId} names.
     *
     * @throws StatusNotFoundException when no catalog row carries that id
     * @throws IllegalArgumentException when the row belongs to another family, carries no code, or carries a
     *     code {@link SalesOrderItemState} does not declare
     */
    public SalesOrderItemState requireItemState(UUID statusId) {
        return documentStateCatalog.requireState(StatusSubject.SALES_ORDER_ITEM, statusId, SalesOrderItemState.class);
    }
}
