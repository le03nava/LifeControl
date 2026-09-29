package com.lifecontrol.api.scheduling.dto;

import java.util.UUID;

/**
 * One appointment as the calendar renders it (D34): the lifecycle fields a grid needs, with the
 * status and customer names resolved by the service.
 *
 * <p>The same appointment still travels through {@link SchedulingAppointmentResponse} on its own
 * lifecycle endpoints; this narrower record exists because the calendar only draws the slot grouping
 * and the names, and because its soft-deleted rows must keep their {@code enabled} flag (D36).</p>
 */
public record SchedulingCalendarAppointmentResponse(
        UUID id,
        String userId,
        UUID customerId,
        String customerName,
        UUID statusId,
        String statusName,
        String notes,
        boolean enabled) {}
