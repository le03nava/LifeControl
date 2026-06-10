package com.lifecontrol.api.salesorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
        BigDecimal totalAmount,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<SalesOrderItemResponse> items
) {}
