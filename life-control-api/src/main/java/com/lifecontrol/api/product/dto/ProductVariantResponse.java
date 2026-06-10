package com.lifecontrol.api.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record ProductVariantResponse(
        UUID id,
        UUID productId,
        UUID companyStoreId,
        String barCode,
        String sku,
        String variantName,
        BigDecimal listPrice,
        BigDecimal costPrice,
        BigDecimal stock,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
