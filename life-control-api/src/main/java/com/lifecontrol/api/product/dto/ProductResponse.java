package com.lifecontrol.api.product.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record ProductResponse(
    UUID id,
    String sku,
    String name,
    String shortName,
    String satCode,
    String productType,
    Map<String, Object> attributes,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
