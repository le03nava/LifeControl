package com.lifecontrol.api.purchaseorder.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

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
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
