package com.lifecontrol.api.company.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateCompanyZoneRequest(
    @NotBlank(message = "zoneCode es requerido")
    @Size(max = 10, message = "zoneCode no puede exceder 10 caracteres")
    String zoneCode,

    @NotBlank(message = "zoneName es requerido")
    @Size(max = 100, message = "zoneName no puede exceder 100 caracteres")
    String zoneName,

    @Size(max = 255, message = "description no puede exceder 255 caracteres")
    String description,

    @Positive(message = "displayOrder debe ser un número positivo")
    Integer displayOrder
) {}
