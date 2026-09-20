package com.lifecontrol.api.goodsreceipt.exception;

import java.util.UUID;

/**
 * {@code 400} when a reception line's purchase-order detail sits in a status that cannot reach a
 * received state (for example {@code Cancelled} or {@code Rejected}).
 *
 * <p>{@code PurchaseOrderService#requireDetailReceivable} owns the rule and throws its typed
 * {@code InvalidStatusTransitionException}; this class adds the line index the operator needs, since
 * the receipt is rejected before any write. Letting the failure surface later would mean letting
 * {@code PurchaseOrderService#registerReceivedQuantity} reject it after the receipt row and the
 * inventory effect were already written.</p>
 */
public class UnreceivableDetailStatusException extends GoodsReceiptValidationException {

    public UnreceivableDetailStatusException(
            int lineIndex, UUID purchaseOrderDetailId, String reason, Throwable cause) {
        super(
                "Reception line " + lineIndex + " (purchase order detail " + purchaseOrderDetailId
                        + ") cannot receive goods: " + reason,
                cause);
    }
}
