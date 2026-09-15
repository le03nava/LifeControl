package com.lifecontrol.api.status.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record StatusRequest(
        @NotBlank(message = "statusName is required") String statusName,
        @NotNull(message = "statusTypeId is required") UUID statusTypeId,
        Boolean enabled) {

    public StatusRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
