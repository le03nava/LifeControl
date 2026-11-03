package com.lifecontrol.api.goodsreceipt.model;

import com.lifecontrol.api.common.state.DocumentState;
import com.lifecontrol.api.common.state.StatusSubject;
import java.util.Set;

/**
 * The state machine of a goods receipt (ADR-0004 decisions 1–2): one state, and it is terminal.
 *
 * <p><b>One constant is the honest count, and inventing a second would be this ADR's own defect.</b>
 * {@code V12:58-64} seeds exactly {@code Registered}, the product has never asked for a cancellation of
 * a receipt, and a typed machine that pre-declares a lifecycle nobody requested is the same error as a
 * workflow engine: a specification with no caller. The receipt is a numbered commerce document
 * (ADR-0003) and therefore in scope for a typed state; it is in scope with the one state it has.</p>
 *
 * <p>A receipt is written by the receipt flow and never moved afterwards, so an empty successor set is
 * not a gap to fill later — it is the decision, and the day a receipt gains a second state that slice
 * adds the constant here, the edge, and the migration's {@code CHECK}.</p>
 */
public enum GoodsReceiptState implements DocumentState<GoodsReceiptState> {
    /** The receipt exists; it records what arrived and is never amended. */
    REGISTERED;

    @Override
    public StatusSubject subject() {
        return StatusSubject.GOODS_RECEIPT;
    }

    @Override
    public Set<GoodsReceiptState> successors() {
        return switch (this) {
            case REGISTERED -> Set.of();
        };
    }
}
