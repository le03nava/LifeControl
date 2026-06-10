package com.lifecontrol.api.salesorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

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
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
