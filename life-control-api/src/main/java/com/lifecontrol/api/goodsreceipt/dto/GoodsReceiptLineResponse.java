package com.lifecontrol.api.goodsreceipt.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a persisted goods receipt.
 *
 * <p>{@code unitCost} is the purchase price the line was received at — {@code purchase_order_details.unit_price},
 * the base and never the taxed total — snapshotted when the receipt was written and never updated since (the
 * column is {@code updatable = false}). It is exposed because the snapshot exists to be auditable: a number no
 * read route returns is a number nobody can check against the movement it produced
 * ({@code odd/tasks/purchase-cost-into-inventory.md}, S8). {@code null} on the receipts written before
 * {@code V96}, which received their units at a price that was never recorded. Appended last, so a client that
 * predates it keeps reading every field it knew.</p>
 */
public record GoodsReceiptLineResponse(
        UUID id,
        UUID purchaseOrderDetailId,
        UUID productVariantId,
        BigDecimal quantityReceived,
        String comments,
        BigDecimal unitCost) {

    /** The pre-snapshot shape: a caller that does not report the price states {@code unitCost = null}. */
    public GoodsReceiptLineResponse(
            UUID id, UUID purchaseOrderDetailId, UUID productVariantId, BigDecimal quantityReceived, String comments) {
        this(id, purchaseOrderDetailId, productVariantId, quantityReceived, comments, null);
    }
}
