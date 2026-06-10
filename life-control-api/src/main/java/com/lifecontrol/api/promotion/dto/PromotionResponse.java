package com.lifecontrol.api.promotion.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PromotionResponse(
    UUID id,
    String promotionName,
    String discountType,
    BigDecimal discountValue,
    String couponCode,
    LocalDateTime startDate,
    LocalDateTime endDate,
    String salesChannel,
    BigDecimal minimumPurchaseAmount,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
