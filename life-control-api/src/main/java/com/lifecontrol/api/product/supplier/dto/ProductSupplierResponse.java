package com.lifecontrol.api.product.supplier.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record ProductSupplierResponse(
    UUID id,
    UUID productId,
    UUID supplierId,
    String supplierName,
    BigDecimal purchaseCost,
    Boolean main,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
