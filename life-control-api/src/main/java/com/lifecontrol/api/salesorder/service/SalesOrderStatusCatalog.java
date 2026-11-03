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
 */
@Component
public class SalesOrderStatusCatalog {

    private final DocumentStateCatalog documentStateCatalog;

    public SalesOrderStatusCatalog(DocumentStateCatalog documentStateCatalog) {
        this.documentStateCatalog = documentStateCatalog;
    }

    /**
     * The state a sales order's {@code status_id} names.
     *
     * @throws StatusNotFoundException when no catalog row carries that id
     * @throws IllegalArgumentException when the row belongs to another family, carries no code, or carries a
     *     code {@link SalesOrderState} does not declare
     */
    public SalesOrderState requireOrderState(UUID statusId) {
        return documentStateCatalog.requireState(StatusSubject.SALES_ORDER, statusId, SalesOrderState.class);
    }

    /**
     * The state a sales-order line's {@code status_id} names.
     *
     * @throws StatusNotFoundException when no catalog row carries that id
     * @throws IllegalArgumentException when the row belongs to another family, carries no code, or carries a
     *     code {@link SalesOrderItemState} does not declare
     */
    public SalesOrderItemState requireItemState(UUID statusId) {
        return documentStateCatalog.requireState(StatusSubject.SALES_ORDER_ITEM, statusId, SalesOrderItemState.class);
    }
}
