package com.lifecontrol.api.measureunit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MeasureUnitRequest(

    @NotBlank(message = "measureUnitName es requerido")
    @Size(max = 100, message = "measureUnitName no puede exceder 100 caracteres")
    String measureUnitName,

    @NotBlank(message = "measureUnitShortName es requerido")
    @Size(max = 10, message = "measureUnitShortName no puede exceder 10 caracteres")
    String measureUnitShortName,

    @NotBlank(message = "unitType es requerido")
    String unitType,

    @NotBlank(message = "satCode es requerido")
    @Size(max = 5, message = "satCode no puede exceder 5 caracteres")
    String satCode,

    @Size(max = 255)
    String description

) {}
