package com.lifecontrol.api.scheduling.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One availability window, as returned by the read and write operations.
 *
 * <p>{@code dayOfWeek} is ISO-8601 {@code 1..7}. The values are wall-clock and carry no zone, so the
 * JSON is an ISO-8601 string ({@code "09:00:00"} for a time, {@code "2026-09-28"} for a date).</p>
 */
public record SchedulingAvailabilityWindowResponse(
        UUID id, int dayOfWeek, LocalTime startTime, LocalTime endTime, LocalDate validFrom, LocalDate validTo) {}
