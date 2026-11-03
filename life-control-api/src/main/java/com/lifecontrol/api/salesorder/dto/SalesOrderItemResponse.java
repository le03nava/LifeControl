package com.lifecontrol.api.salesorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One line of a sales order, as the API answers it.
 *
 * @param statusId the catalog row the line's state is labelled by, <b>projected from {@code status}</b>: the
 *     line stores no pointer any more, so the pair is a lookup by code in the line's own family
 * @param statusName the label of that row — presentation, never the line's state
 * @param status the line's typed state as its code (ADR-0004 decision 3) — {@code PENDING}, {@code ADDED} or
 *     {@code CANCELLED} — and the authority the pair is derived from
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
