package com.lifecontrol.api.scheduling.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Status change request: the target status id. Its type is validated as {@code APPOINTMENT} and the
 * edge is checked against the transition map (D24).
 */
public record SchedulingAppointmentStatusRequest(
        @NotNull(message = "statusId is required") UUID statusId) {}
