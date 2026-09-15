package com.lifecontrol.api.country.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CountryRequest(
        @NotBlank(message = "countryCode is required")
        @Size(min = 2, max = 2, message = "countryCode must have exactly 2 characters (ISO 3166-1 alpha-2)")
        String countryCode,

        @NotBlank(message = "countryName is required")
        @Size(max = 100, message = "countryName must not exceed 100 characters")
        String countryName) {}
