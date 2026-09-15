package com.lifecontrol.api.company.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateCompanyZoneRequest(
        @NotBlank(message = "zoneCode is required") @Size(max = 10, message = "zoneCode must not exceed 10 characters")
        String zoneCode,

        @NotBlank(message = "zoneName is required")
        @Size(max = 100, message = "zoneName must not exceed 100 characters")
        String zoneName,

        @Size(max = 255, message = "description must not exceed 255 characters")
        String description,

        @Positive(message = "displayOrder must be a positive number")
        Integer displayOrder) {}
