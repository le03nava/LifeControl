package com.lifecontrol.api.store.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStoreAreaRequest(
        @NotBlank(message = "areaCode is required") @Size(max = 10)
        String areaCode,

        @NotBlank(message = "areaName is required") @Size(max = 100)
        String areaName,

        @Size(max = 255) String description,
        @Min(0) Integer displayOrder) {}
