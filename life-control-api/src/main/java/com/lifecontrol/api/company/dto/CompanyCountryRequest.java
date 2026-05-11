package com.lifecontrol.api.company.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyCountryRequest(

    @NotBlank(message = "countryCode es requerido")
    @Size(min = 2, max = 2, message = "countryCode debe tener exactamente 2 caracteres (ISO 3166-1 alpha-2)")
    String countryCode,

    @Size(max = 200, message = "localAlias no puede exceder 200 caracteres")
    String localAlias

) {}
