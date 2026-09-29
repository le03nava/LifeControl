package com.lifecontrol.api.scheduling.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Booking request for an appointment.
 *
 * <p>It deliberately carries no store: the store is derived from the slot's activity, which is what
 * makes the booking path verify the caller against the store the slot actually belongs to instead of
 * one the client claims. It carries no status either: every booking is created as {@code Scheduled}
 * (D23).</p>
 *
 * <p>{@code userId} is the optional Keycloak {@code sub} of the attendee and stays unassigned when
 * omitted (D30); {@code customerId} is validated for existence when present (D31).</p>
 */
public record SchedulingAppointmentRequest(
        @NotNull(message = "slotId is required") UUID slotId, String userId, UUID customerId, String notes) {}
