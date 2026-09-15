package com.lifecontrol.api.company.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyCountryRequest(
        @NotBlank(message = "countryCode is required")
                @Size(min = 2, max = 2, message = "countryCode must have exactly 2 characters (ISO 3166-1 alpha-2)")
                String countryCode,
        @Size(max = 200, message = "localAlias must not exceed 200 characters") String localAlias) {}
