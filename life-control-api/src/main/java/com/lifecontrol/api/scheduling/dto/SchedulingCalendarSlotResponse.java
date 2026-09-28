package com.lifecontrol.api.scheduling.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One calendar entry: a materialized slot plus its own appointment list (D34).
 *
 * <p>{@code available} is derived as {@code capacity - booked} and is never a stored column (D21).
 * {@code status} is the slot's own {@code 'Available'}/{@code 'Full'} string. A slot with no
 * appointments is still an entry with an empty {@code appointments} list, because a calendar grid
 * must render it.</p>
 *
 * <p>The list carries soft-deleted appointments with their {@code enabled} flag (D36): a
 * soft-deleted {@code Completed} appointment still holds capacity and is still counted by
 * {@code booked}, so hiding it would make the entry contradict the counter it renders.</p>
 *
 * <p>{@code activityEnabled} is the joined activity's own flag (D37), exposed rather than filtered
 * on: deleting an activity is a soft delete that only flips that flag and deletes no slot, so the
 * calendar renders its slots and lets the client decide what to draw or to offer for booking.</p>
 */
public record SchedulingCalendarSlotResponse(
        UUID slotId,
        UUID activityId,
        String activityName,
        boolean activityEnabled,
        LocalDateTime startAt,
        LocalDateTime endAt,
        int capacity,
        int booked,
        int available,
        String status,
        List<SchedulingCalendarAppointmentResponse> appointments) {}
