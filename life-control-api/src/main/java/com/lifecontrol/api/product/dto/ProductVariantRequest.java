package com.lifecontrol.api.product.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductVariantRequest(
        @NotNull UUID productId,
        @NotNull UUID companyStoreId,
        String barCode,
        String sku,
        String variantName,
        BigDecimal listPrice,
        BigDecimal costPrice,
        BigDecimal stock,
        Boolean enabled
) {
    public ProductVariantRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
