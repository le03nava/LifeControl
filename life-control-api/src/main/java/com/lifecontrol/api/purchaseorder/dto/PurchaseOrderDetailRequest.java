package com.lifecontrol.api.purchaseorder.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseOrderDetailRequest(

    @NotNull(message = "productId es requerido")
    UUID productId,

    @NotNull(message = "quantity es requerido")
    @Min(value = 1, message = "quantity debe ser mayor a 0")
    Integer quantity,

    @NotNull(message = "unitPrice es requerido")
    @DecimalMin(value = "0.01", message = "unitPrice debe ser mayor o igual a 0.01")
    BigDecimal unitPrice,

    String comments,

    UUID statusId

) {}
