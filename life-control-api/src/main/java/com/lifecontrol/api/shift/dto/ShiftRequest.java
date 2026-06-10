package com.lifecontrol.api.shift.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ShiftRequest(

    @NotNull(message = "companyStoreId is required")
    UUID companyStoreId,

    String userId,

    Boolean enabled

) {
    public ShiftRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
