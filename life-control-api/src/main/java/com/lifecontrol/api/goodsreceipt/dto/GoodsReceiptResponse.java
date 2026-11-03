package com.lifecontrol.api.goodsreceipt.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A persisted goods receipt, with the order number and status name denormalized for the client.
 *
 * @param statusId the catalog row the receipt's state is labelled by, <b>projected from {@code status}</b>: the
 *     receipt stores no pointer any more, so the pair is a lookup by code in the goods-receipt family
 * @param statusName the label of that row — presentation, never the receipt's state
 * @param status the receipt's typed state as its code (ADR-0004 decision 1) — {@code REGISTERED}, never the
 *     catalog's label — and the authority the pair is derived from. A receipt has one state and is never moved,
 *     so this code is always the one it was born in
 */
public record GoodsReceiptResponse(
        UUID id,
        String receiptNumber,
        UUID purchaseOrderId,
        String orderNumber,
        UUID companyStoreId,
        UUID receivingLocationId,
        UUID statusId,
        String statusName,
        String status,
        String receivedBy,
        LocalDateTime receivedAt,
        String comments,
        Boolean enabled,
        List<GoodsReceiptLineResponse> lines) {}
