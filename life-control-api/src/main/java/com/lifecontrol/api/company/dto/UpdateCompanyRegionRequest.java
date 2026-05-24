package com.lifecontrol.api.company.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCompanyRegionRequest(
    @NotBlank(message = "regionCode es requerido")
    @Size(max = 10, message = "regionCode no puede exceder 10 caracteres")
    String regionCode,

    @NotBlank(message = "regionName es requerido")
    @Size(max = 100, message = "regionName no puede exceder 100 caracteres")
    String regionName
) {}
