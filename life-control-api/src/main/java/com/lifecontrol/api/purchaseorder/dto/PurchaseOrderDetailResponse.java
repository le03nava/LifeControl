package com.lifecontrol.api.purchaseorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One purchase-order line, as the API answers it.
 *
 * @param status the line's typed state as its code (ADR-0004 decision 3) — {@code PENDING},
 *     {@code IN_PROCESS}, {@code IN_TRANSIT}, {@code PARTIAL_RECEIVED}, {@code RECEIVED}, {@code REJECTED} or
 *     {@code CANCELLED}. This machine is <b>not</b> the sales-order item set: it has its own tail and no
 *     {@code ADDED}, and {@code statusId}/{@code statusName} are unchanged beside it until T8 drops the pointer
 */
public record PurchaseOrderDetailResponse(
        UUID id,
        UUID purchaseOrderId,
        UUID productId,
        String productName,
        UUID productVariantId,
        String productVariantName,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal total,
        BigDecimal receivedQuantity,
        String comments,
        UUID statusId,
        String statusName,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
