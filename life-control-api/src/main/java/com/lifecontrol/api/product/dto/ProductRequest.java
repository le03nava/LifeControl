package com.lifecontrol.api.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record ProductRequest(
        @NotBlank(message = "sku is required") @Size(max = 50, message = "sku must not exceed 50 characters")
        String sku,

        @NotBlank(message = "name is required") @Size(max = 255, message = "name must not exceed 255 characters")
        String name,

        @Size(max = 100, message = "shortName must not exceed 100 characters")
        String shortName,

        @Size(max = 20, message = "satCode must not exceed 20 characters")
        String satCode,

        @Size(max = 50, message = "productType must not exceed 50 characters")
        String productType,

        Map<String, Object> attributes) {}
