package com.lifecontrol.api.hr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Write payload for a department, shared by create and update.
 *
 * <p>The company domain splits create from update so the update request can carry a version
 * precondition. Departments have no {@code version} column and therefore no 412 precondition
 * (record T2), so one request record covers both operations instead of a split whose only purpose
 * would be a precondition this catalog does not have.</p>
 *
 * <p>{@code enabled} defaults to {@code true} when omitted, so an explicit {@code false} is required
 * to create a disabled department. {@code description} and {@code displayOrder} are optional.</p>
 */
public record DepartmentRequest(
        @NotBlank(message = "departmentCode is required") @Size(max = 10)
        String departmentCode,

        @NotBlank(message = "departmentName is required") @Size(max = 100)
        String departmentName,

        @Size(max = 255) String description,

        @Positive(message = "displayOrder must be positive") Integer displayOrder,

        Boolean enabled) {

    public DepartmentRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
