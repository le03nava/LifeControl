package com.lifecontrol.api.measureunit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MeasureUnitRequest(
        @NotBlank(message = "measureUnitName is required")
                @Size(max = 100, message = "measureUnitName must not exceed 100 characters")
                String measureUnitName,
        @NotBlank(message = "measureUnitShortName is required")
                @Size(max = 10, message = "measureUnitShortName must not exceed 10 characters")
                String measureUnitShortName,
        @NotBlank(message = "unitType is required") String unitType,
        @NotBlank(message = "satCode is required") @Size(max = 5, message = "satCode must not exceed 5 characters")
                String satCode,
        @Size(max = 255) String description) {}
