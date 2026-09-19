package com.lifecontrol.api.purchaseorder.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseOrderDetailRequest(
        @NotNull(message = "productId is required") UUID productId,

        UUID productVariantId,

        @NotNull(message = "quantity is required") @Min(value = 1, message = "quantity must be greater than 0")
        Integer quantity,

        @NotNull(message = "unitPrice is required")
        @DecimalMin(value = "0.01", message = "unitPrice must be greater than or equal to 0.01")
        BigDecimal unitPrice,

        String comments,
        UUID statusId) {}
