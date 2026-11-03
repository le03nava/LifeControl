package com.lifecontrol.api.salesorder.model;

import com.lifecontrol.api.common.state.DocumentState;
import com.lifecontrol.api.common.state.StatusSubject;
import java.util.Set;

/**
 * The state machine of a sales-order line (ADR-0004 decision 3), copied edge for edge from
 * {@code SalesOrderService.SO_ITEM_TRANSITIONS}.
 *
 * <p>The line is the unit that moves stock: {@code PENDING} holds nothing, {@code ADDED} is the line
 * whose deduction is booked, and {@code CANCELLED} is the line whose reversal is booked. "An item of a
 * charged order cannot be cancelled" stops being a {@code !"Cancelled".equals(…)} test and becomes the
 * absence of an edge out of {@code ADDED}.</p>
 */
public enum SalesOrderItemState implements DocumentState<SalesOrderItemState> {
    /** The line exists and holds no stock. */
    PENDING,
    /** The line's deduction is booked. */
    ADDED,
    /** Final. The line's reversal is booked. */
    CANCELLED;

    @Override
    public StatusSubject subject() {
        return StatusSubject.SALES_ORDER_ITEM;
    }

    @Override
    public Set<SalesOrderItemState> successors() {
        return switch (this) {
            case PENDING -> Set.of(ADDED, CANCELLED);
            case ADDED, CANCELLED -> Set.of();
        };
    }
}
