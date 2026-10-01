package com.lifecontrol.api.hr.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Write payload for a seniority level. {@code enabled} defaults to {@code true} when omitted, so an
 * explicit {@code false} is required to create a disabled level.
 */
public record SeniorityLevelRequest(
        @NotBlank(message = "levelCode is required") @Size(max = 10)
        String levelCode,

        @NotBlank(message = "levelName is required") @Size(max = 50)
        String levelName,

        @NotNull(message = "rank is required") @Min(value = 1, message = "rank must be at least 1")
        Integer rank,

        Boolean enabled) {

    public SeniorityLevelRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
