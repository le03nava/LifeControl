package com.lifecontrol.api.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStoreZoneRequest(
        @NotBlank(message = "zoneCode is required") @Size(max = 10)
        String zoneCode,

        @NotBlank(message = "zoneName is required") @Size(max = 100)
        String zoneName,

        @Size(max = 255) String description,
        Integer displayOrder) {}
