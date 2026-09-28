package com.lifecontrol.api.scheduling.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body of the scheduling-activity create and update operations.
 *
 * <p>{@code companyStoreId} identifies the store the activity belongs to. It is <b>required on
 * create</b> and validated by the service, which resolves the store and authorizes the caller for
 * it before writing. On <b>update the store is immutable and this field is ignored</b>: the activity
 * keeps the store it was created in, so a client can round-trip a GET response into a PUT without
 * earning a 400 for a field it never meant to change.</p>
 *
 * <p>{@code userId} is the Keycloak {@code sub} of the employee who attends the activity; nullable,
 * because the catalogue entry can exist before someone is assigned.</p>
 *
 * <p>{@code version} is an <b>optional precondition</b>, the body equivalent of an {@code If-Match}
 * header. Absent ({@code null}) means "no precondition". Present means "reject the update with a 412
 * if the stored activity is not at this version", which lets a client that read a version detect and
 * refuse a lost update.</p>
 *
 * <p>{@code enabled} is optional on create and defaults to {@code true}; on update a {@code null}
 * leaves the current enabled state untouched.</p>
 */
public record SchedulingActivityRequest(
        UUID companyStoreId,

        String userId,

        @NotBlank(message = "activityName is required")
        @Size(max = 150, message = "activityName must be at most 150 characters")
        String activityName,

        @Size(max = 2000, message = "description must be at most 2000 characters")
        String description,

        @NotNull(message = "durationMinutes is required")
        @Min(value = 1, message = "durationMinutes must be at least 1")
        Integer durationMinutes,

        @NotNull(message = "capacityPerSlot is required")
        @Min(value = 1, message = "capacityPerSlot must be at least 1")
        Integer capacityPerSlot,

        Boolean enabled,

        Long version) {}
