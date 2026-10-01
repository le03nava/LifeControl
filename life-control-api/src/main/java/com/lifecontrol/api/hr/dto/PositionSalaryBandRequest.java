package com.lifecontrol.api.hr.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One salary band inside the full-set save request.
 *
 * <p>The item carries no {@code enabled} field on purpose: presence in the request list means the
 * band is enabled, and omission means it is disabled (decision D13). Exposing a second way to say
 * the same thing would let a client submit an "enabled" item that the omission rule then clears.</p>
 *
 * <p>The amounts are required; the range — non-negative minimum and maximum at or above the minimum —
 * is validated by the service so a violation is a 400 (the {@code IllegalArgumentException} path)
 * rather than the database CHECK's 409.</p>
 */
public record PositionSalaryBandRequest(
        @NotNull(message = "seniorityLevelId is required") UUID seniorityLevelId,

        @NotNull(message = "minimumSalary is required") BigDecimal minimumSalary,

        @NotNull(message = "maximumSalary is required") BigDecimal maximumSalary) {}
