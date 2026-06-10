package com.lifecontrol.api.salesorder.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record SalesOrderItemRequest(
        @NotNull(message = "productVariantId is required") UUID productVariantId,
        @NotNull(message = "quantity is required") @DecimalMin(value = "0.01", message = "quantity must be greater than 0") BigDecimal quantity,
        @NotNull(message = "listPrice is required") @DecimalMin(value = "0.01", message = "listPrice must be greater than 0") BigDecimal listPrice,
        BigDecimal discountApplied,
        UUID promotionId
) {
    public SalesOrderItemRequest {
        if (discountApplied == null) {
            discountApplied = BigDecimal.ZERO;
        }
    }
}
