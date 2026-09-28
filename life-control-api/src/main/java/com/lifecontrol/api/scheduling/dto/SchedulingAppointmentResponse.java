package com.lifecontrol.api.scheduling.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One appointment, as returned by the booking and lifecycle endpoints.
 *
 * <p>{@code startAt}/{@code endAt} are read from the referenced slot, so the client sees the booked
 * window without a second request. {@code statusName} is resolved from {@code statusId} by the
 * service; the id remains the client's handle for transitions. {@code userId} and {@code customerId}
 * are nullable, and activity/customer names are deliberately absent — they belong to W4b's
 * projection, not to this lifecycle slice.</p>
 */
public record SchedulingAppointmentResponse(
        UUID id,
        UUID slotId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        UUID activityId,
        UUID companyStoreId,
        String userId,
        UUID customerId,
        UUID statusId,
        String statusName,
        String notes,
        boolean enabled,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
