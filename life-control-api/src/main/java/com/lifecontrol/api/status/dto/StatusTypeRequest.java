package com.lifecontrol.api.status.dto;

import jakarta.validation.constraints.NotBlank;

public record StatusTypeRequest(

    @NotBlank(message = "statusTypeName es requerido")
    String statusTypeName,

    Boolean enabled

) {
    public StatusTypeRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
