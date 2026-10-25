package com.lifecontrol.api.purchaseorder.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseOrderDetailRequest(
        @NotNull(message = "productId is required") UUID productId,

        @NotNull(message = "productVariantId is required") UUID productVariantId,

        @NotNull(message = "quantity is required")
        @DecimalMin(value = "0.01", message = "quantity must be greater than 0")
        @Digits(integer = 10, fraction = 2, message = "quantity must have at most two decimals")
        BigDecimal quantity,

        @NotNull(message = "unitPrice is required")
        @DecimalMin(value = "0.01", message = "unitPrice must be greater than or equal to 0.01")
        BigDecimal unitPrice,

        String comments,
        UUID statusId) {}
