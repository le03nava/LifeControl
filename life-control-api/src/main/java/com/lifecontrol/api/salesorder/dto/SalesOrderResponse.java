package com.lifecontrol.api.salesorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One sales order, as the API answers it.
 *
 * @param statusId the catalog row the order's state is labelled by, <b>projected from {@code status}</b>: the
 *     document stores no pointer any more (ADR-0004 step 9 dropped {@code status_id}), so the pair below is a
 *     lookup by code in the family's own catalog rows
 * @param statusName the label of that row — presentation, and a rename here changes what the screen says and
 *     never what the order is. A screen may read {@code status} or this pair; both are answered on purpose
 * @param status the order's typed state as its code (ADR-0004 decision 1) — {@code DRAFT}, {@code ACTIVE},
 *     {@code PENDING}, {@code COMPLETED} or {@code CANCELLED}, never the catalog's label — and the authority
 */
public record SalesOrderResponse(
        UUID id,
        String orderNumber,
        UUID customerId,
        UUID companyStoreId,
        UUID shiftId,
        String userId,
        LocalDateTime orderDate,
        UUID statusId,
        String statusName,
        String status,
        BigDecimal totalAmount,
        UUID paymentMethodId,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<SalesOrderItemResponse> items) {}
