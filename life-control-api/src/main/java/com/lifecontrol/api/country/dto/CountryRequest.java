package com.lifecontrol.api.country.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CountryRequest(

    @NotBlank(message = "countryCode es requerido")
    @Size(min = 2, max = 2, message = "countryCode debe tener exactamente 2 caracteres (ISO 3166-1 alpha-2)")
    String countryCode,

    @NotBlank(message = "countryName es requerido")
    @Size(max = 100, message = "countryName no puede exceder 100 caracteres")
    String countryName

) {}
