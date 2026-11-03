package com.lifecontrol.api.salesorder.model;

import com.lifecontrol.api.common.state.DocumentState;
import com.lifecontrol.api.common.state.StatusSubject;
import java.util.Set;

/**
 * The state machine of a sales order (ADR-0004 decisions 1–2), the successor sets copied edge for edge
 * from {@code SalesOrderService.SO_TRANSITIONS} as it stood on 2026-11-03.
 *
 * <p>A sale is drafted, activated, left pending while it is being paid, and then either completed or
 * cancelled. {@code COMPLETED} and {@code CANCELLED} are terminal: the stock a completed sale moved is
 * what makes a second move unsafe, and a cancellation is the reversal that already happened.</p>
 */
public enum SalesOrderState implements DocumentState<SalesOrderState> {
    /** Editable, reserves nothing, sells nothing. */
    DRAFT,
    /** Open for lines and payment; the state a store works in. */
    ACTIVE,
    /** Charged and awaiting the confirmation that closes it. */
    PENDING,
    /** Final. Stock moved, payments recorded, no further move. */
    COMPLETED,
    /** Final. The reversal is the recorded fact; the state does not reopen. */
    CANCELLED;

    @Override
    public StatusSubject subject() {
        return StatusSubject.SALES_ORDER;
    }

    @Override
    public Set<SalesOrderState> successors() {
        return switch (this) {
            case DRAFT -> Set.of(ACTIVE, CANCELLED);
            case ACTIVE -> Set.of(PENDING, CANCELLED);
            case PENDING -> Set.of(COMPLETED, CANCELLED);
            case COMPLETED, CANCELLED -> Set.of();
        };
    }
}
