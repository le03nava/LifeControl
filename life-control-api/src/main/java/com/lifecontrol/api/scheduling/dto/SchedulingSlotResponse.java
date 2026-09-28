package com.lifecontrol.api.scheduling.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One bookable slot, as returned by the range read.
 *
 * <p>{@code available} is derived at mapping time as {@code capacity - booked} and is never a column:
 * the two stored numbers are the source of truth and a stored third one could drift from them.</p>
 *
 * <p>{@code startAt}/{@code endAt} are store-local wall-clock date-times and serialize as ISO-8601
 * strings with no offset.</p>
 */
public record SchedulingSlotResponse(
        UUID id,
        UUID activityId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        int capacity,
        int booked,
        int available,
        String status,
        boolean enabled) {}
