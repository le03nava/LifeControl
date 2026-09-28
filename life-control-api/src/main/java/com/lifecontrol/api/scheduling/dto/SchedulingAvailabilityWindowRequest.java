package com.lifecontrol.api.scheduling.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One availability window of a replace-availability request.
 *
 * <p>{@code dayOfWeek} is ISO-8601 — {@code 1} is Monday and {@code 7} is Sunday — and the range is
 * asserted here so an out-of-range day is a bean-validation 400. The domain-level checks the bean
 * cannot express ({@code endTime} after {@code startTime}, {@code validTo} not before
 * {@code validFrom}, and no overlap inside a weekday) are enforced by the service.</p>
 *
 * <p>{@code startTime}/{@code endTime} and {@code validFrom}/{@code validTo} are store-local
 * wall-clock values carried as JSON {@code LocalTime}/{@code LocalDate} (ISO-8601 strings).</p>
 */
public record SchedulingAvailabilityWindowRequest(
        @NotNull(message = "dayOfWeek is required")
        @Min(value = 1, message = "dayOfWeek must be between 1 (Monday) and 7 (Sunday)")
        @Max(value = 7, message = "dayOfWeek must be between 1 (Monday) and 7 (Sunday)")
        Integer dayOfWeek,

        @NotNull(message = "startTime is required") LocalTime startTime,

        @NotNull(message = "endTime is required") LocalTime endTime,

        @NotNull(message = "validFrom is required") LocalDate validFrom,

        @NotNull(message = "validTo is required") LocalDate validTo) {}
