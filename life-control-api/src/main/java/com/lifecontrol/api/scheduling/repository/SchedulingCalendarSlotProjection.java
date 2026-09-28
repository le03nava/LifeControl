package com.lifecontrol.api.scheduling.repository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of the calendar's slot read: a materialized slot joined to its activity's name.
 *
 * <p>D35's first batched read projects straight into this record through a JPQL constructor
 * expression, so the slot part of the projection carries {@code activityName} without a second query
 * per row. The service derives {@code available = capacity - booked} (D21) and attaches each slot's
 * appointments after the second batched read.</p>
 *
 * <p>{@code activityEnabled} carries the joined activity's {@code enabled} flag (D37). The read
 * never filters by it: deleting an activity is a soft delete that only flips that flag and deletes
 * no slot, so filtering it out would hide every materialized slot of a retired activity, including
 * the booked ones whose {@code booked}/{@code available} numbers the calendar renders.</p>
 */
public record SchedulingCalendarSlotProjection(
        UUID slotId,
        UUID activityId,
        String activityName,
        boolean activityEnabled,
        LocalDateTime startAt,
        LocalDateTime endAt,
        int capacity,
        int booked,
        String status) {}
