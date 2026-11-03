package com.lifecontrol.api.salesorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One line of a sales order, as the API answers it.
 *
 * @param status the line's typed state as its code (ADR-0004 decision 3) — {@code PENDING}, {@code ADDED} or
 *     {@code CANCELLED} — which is added beside {@code statusId}/{@code statusName} and changes neither, so
 *     the screens that already read the pair keep working until T8 drops the pointer
 */
public record SalesOrderItemResponse(
        UUID id,
        UUID salesOrderId,
        UUID productVariantId,
        BigDecimal quantity,
        BigDecimal listPrice,
        BigDecimal discountApplied,
        BigDecimal finalPrice,
        UUID promotionId,
        UUID statusId,
        String statusName,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
