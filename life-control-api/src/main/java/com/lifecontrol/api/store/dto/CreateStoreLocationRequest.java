package com.lifecontrol.api.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStoreLocationRequest(
        @NotBlank(message = "locationCode is required") @Size(max = 10)
        String locationCode,

        @NotBlank(message = "locationName is required") @Size(max = 100)
        String locationName,

        @Size(max = 255) String description,
        Integer displayOrder) {}
