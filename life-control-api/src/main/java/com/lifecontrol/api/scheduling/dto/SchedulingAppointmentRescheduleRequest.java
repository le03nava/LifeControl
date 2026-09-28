package com.lifecontrol.api.scheduling.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Reschedule request: the slot the appointment moves to. Only the slot is carried — a reschedule
 * keeps the appointment's status (D29).
 */
public record SchedulingAppointmentRescheduleRequest(
        @NotNull(message = "slotId is required") UUID slotId) {}
