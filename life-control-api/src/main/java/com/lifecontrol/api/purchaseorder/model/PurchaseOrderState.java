package com.lifecontrol.api.purchaseorder.model;

import com.lifecontrol.api.common.state.DocumentState;
import com.lifecontrol.api.common.state.StatusSubject;
import java.util.Set;

/**
 * The state machine of a purchase order (ADR-0004 decisions 1–2), copied edge for edge from
 * {@code PurchaseOrderService.PO_TRANSITIONS}.
 *
 * <p>Eight states with one linear trunk — sent, accepted, in transit, received, billed, closed — and
 * {@code REJECTED} reachable from every non-terminal state, which is the supplier refusing at whatever
 * point the refusal arrives. {@code CLOSED} and {@code REJECTED} are terminal.</p>
 */
public enum PurchaseOrderState implements DocumentState<PurchaseOrderState> {
    /** The order is being written. */
    DRAFT,
    /** Sent to the supplier. */
    SENT,
    /** The supplier accepted it. */
    ACCEPTED,
    /** The goods are on their way. */
    IN_TRANSIT,
    /** The goods arrived; the receipt is the document that records what arrived. */
    RECEIVED,
    /** The supplier invoiced it. */
    BILLED,
    /** Final. The order is administratively done. */
    CLOSED,
    /** Final. The supplier refused; nothing will be received against it. */
    REJECTED;

    @Override
    public StatusSubject subject() {
        return StatusSubject.PURCHASE_ORDER;
    }

    @Override
    public Set<PurchaseOrderState> successors() {
        return switch (this) {
            case DRAFT -> Set.of(SENT, REJECTED);
            case SENT -> Set.of(ACCEPTED, REJECTED);
            case ACCEPTED -> Set.of(IN_TRANSIT, REJECTED);
            case IN_TRANSIT -> Set.of(RECEIVED, REJECTED);
            case RECEIVED -> Set.of(BILLED, REJECTED);
            case BILLED -> Set.of(CLOSED, REJECTED);
            case CLOSED, REJECTED -> Set.of();
        };
    }
}
