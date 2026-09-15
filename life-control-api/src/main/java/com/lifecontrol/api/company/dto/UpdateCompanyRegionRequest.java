package com.lifecontrol.api.company.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCompanyRegionRequest(
        @NotBlank(message = "regionCode is required")
        @Size(max = 10, message = "regionCode must not exceed 10 characters")
        String regionCode,

        @NotBlank(message = "regionName is required")
        @Size(max = 100, message = "regionName must not exceed 100 characters")
        String regionName) {}
