package com.lifecontrol.api.purchaseorder.model;

import com.lifecontrol.api.common.state.DocumentState;
import com.lifecontrol.api.common.state.StatusSubject;
import java.util.Set;

/**
 * The state machine of a purchase-order line (ADR-0004 decision 3), copied edge for edge from
 * {@code PurchaseOrderService.DETAIL_TRANSITIONS}.
 *
 * <p><b>Seven states, and they are not the sales-order item set.</b> The detail machine has its own tail
 * — {@code IN_PROCESS}, {@code PARTIAL_RECEIVED} — and admits no {@code ADDED}; copying the item set here
 * would omit five real states and admit one no detail has ever held. {@code RECEIVED}, {@code REJECTED}
 * and {@code CANCELLED} are terminal.</p>
 */
public enum PurchaseOrderDetailState implements DocumentState<PurchaseOrderDetailState> {
    /** The line exists; nothing has been done with it. */
    PENDING,
    /** The supplier is preparing the line. */
    IN_PROCESS,
    /** The line's goods are on their way. */
    IN_TRANSIT,
    /** Some of the line arrived; the rest is still expected. */
    PARTIAL_RECEIVED,
    /** Final. The whole line arrived. */
    RECEIVED,
    /** Final. The line was refused. */
    REJECTED,
    /** Final. The line was cancelled before arriving. */
    CANCELLED;

    @Override
    public StatusSubject subject() {
        return StatusSubject.PURCHASE_ORDER_DETAIL;
    }

    @Override
    public Set<PurchaseOrderDetailState> successors() {
        return switch (this) {
            case PENDING -> Set.of(IN_PROCESS, CANCELLED);
            case IN_PROCESS -> Set.of(IN_TRANSIT, REJECTED, CANCELLED);
            case IN_TRANSIT -> Set.of(PARTIAL_RECEIVED, REJECTED, CANCELLED);
            case PARTIAL_RECEIVED -> Set.of(RECEIVED, REJECTED);
            case RECEIVED, REJECTED, CANCELLED -> Set.of();
        };
    }
}
