package com.lifecontrol.api.product.supplier.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductSupplierRequest(

    @NotNull(message = "supplierId es requerido")
    UUID supplierId,

    @DecimalMin(value = "0.00", message = "purchaseCost no puede ser negativo")
    BigDecimal purchaseCost,

    Boolean main,

    Boolean enabled

) {
    public ProductSupplierRequest {
        if (main == null) {
            main = false;
        }
        if (enabled == null) {
            enabled = true;
        }
    }
}
