package com.lifecontrol.api.promotion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PromotionRequest(

    @NotBlank(message = "promotionName is required")
    String promotionName,

    @NotBlank(message = "discountType is required")
    String discountType,

    @NotNull(message = "discountValue is required")
    BigDecimal discountValue,

    String couponCode,

    LocalDateTime startDate,

    LocalDateTime endDate,

    String salesChannel,

    BigDecimal minimumPurchaseAmount,

    Boolean enabled

) {
    public PromotionRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
