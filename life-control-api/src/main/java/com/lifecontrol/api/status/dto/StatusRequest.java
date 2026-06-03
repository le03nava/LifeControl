package com.lifecontrol.api.status.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record StatusRequest(

    @NotBlank(message = "statusName es requerido")
    String statusName,

    @NotNull(message = "statusTypeId es requerido")
    UUID statusTypeId,

    Boolean enabled

) {
    public StatusRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
