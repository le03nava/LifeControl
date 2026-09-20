package com.lifecontrol.api.goodsreceipt.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** A persisted goods receipt, with the order number and status name denormalized for the client. */
public record GoodsReceiptResponse(
        UUID id,
        String receiptNumber,
        UUID purchaseOrderId,
        String orderNumber,
        UUID companyStoreId,
        UUID receivingLocationId,
        UUID statusId,
        String statusName,
        String receivedBy,
        LocalDateTime receivedAt,
        String comments,
        Boolean enabled,
        List<GoodsReceiptLineResponse> lines) {}
