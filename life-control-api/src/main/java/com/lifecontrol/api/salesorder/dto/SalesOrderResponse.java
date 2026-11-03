package com.lifecontrol.api.salesorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One sales order, as the API answers it.
 *
 * @param status the order's typed state as its code (ADR-0004 decision 1) — {@code DRAFT}, {@code ACTIVE},
 *     {@code PENDING}, {@code COMPLETED} or {@code CANCELLED}, never the catalog's label — added beside
 *     {@code statusId}/{@code statusName}, which are unchanged until T8 drops the pointer
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
