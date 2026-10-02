package com.lifecontrol.api.hr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Write payload for a position, shared by create and update.
 *
 * <p>Positions have no {@code version} column and therefore no 412 precondition (record T2), so one
 * request record covers both operations instead of a split whose only purpose would be a
 * precondition this catalog does not have.</p>
 *
 * <p>{@code departmentId} is required on <b>both</b> create and update because the edit form has a
 * Department field: a move between departments is legal, and the service runs its per-department
 * uniqueness checks against the (possibly new) department. {@code reportsToPositionId} is the
 * optional self-reference; the service validates its existence, its company and the absence of a
 * cycle. {@code enabled} defaults to {@code true} when omitted, so an explicit {@code false} is
 * required to create a disabled position. {@code description} and {@code displayOrder} are
 * optional.</p>
 */
public record PositionRequest(
        @NotNull(message = "departmentId is required") UUID departmentId,

        @NotBlank(message = "positionCode is required") @Size(max = 10)
        String positionCode,

        @NotBlank(message = "positionName is required") @Size(max = 100)
        String positionName,

        @Size(max = 500) String description,

        UUID reportsToPositionId,

        @Positive(message = "displayOrder must be positive") Integer displayOrder,

        Boolean enabled) {

    public PositionRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
