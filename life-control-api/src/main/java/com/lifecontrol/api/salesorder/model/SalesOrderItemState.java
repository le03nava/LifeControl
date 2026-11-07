package com.lifecontrol.api.salesorder.model;

import com.lifecontrol.api.common.state.DocumentState;
import com.lifecontrol.api.common.state.StatusSubject;
import java.util.Set;

/**
 * The state machine of a sales-order line (ADR-0004 decision 3), copied edge for edge from
 * {@code SalesOrderService.SO_ITEM_TRANSITIONS}.
 *
 * <p>The line is the unit whose stock the charge books: {@code PENDING} is a line the charge has not booked yet,
 * {@code ADDED} is a line its charge booked, and {@code CANCELLED} is a line the charge skips — it was cancelled
 * before anything was taken, so it has no movement to reverse (ADR-0002 decision 7). "An item of a charged order
 * cannot be cancelled" stops being a {@code !"Cancelled".equals(…)} test and becomes the absence of an edge out of
 * {@code ADDED}.</p>
 */
public enum SalesOrderItemState implements DocumentState<SalesOrderItemState> {
    /** The line exists and the charge has not booked it. */
    PENDING,
    /** The line's deduction is booked, by the charge and by nothing else. */
    ADDED,
    /** Final. The charge never booked the line, so there is nothing to give back. */
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
