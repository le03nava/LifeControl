package com.lifecontrol.api.hr.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body of the replace-position-roles operation.
 *
 * <p>Mirrors the sibling {@code PositionSalaryBandsRequest}: a list body wrapped in a record. The
 * set is replaced as a whole, but the replacement is an <b>upsert</b> keyed on
 * {@code (position_id, role_name)} (record T20): a stored name the request omits is disabled, never
 * deleted. An empty list therefore clears every configured role without removing a single row.</p>
 *
 * <p>The {@code @Size} cap is 100, matching the salary-band sibling in the same package. It is the
 * sibling's number rather than the other list-body precedent's 50
 * ({@code SchedulingAvailabilityRequest}) because this resource is shaped file-for-file like the
 * bands, and the frozen allowlist already caps the meaningful set at fifteen names: the bound is a
 * defensive limit on the payload, not a product constraint.</p>
 */
public record PositionRolesRequest(
        @NotNull(message = "roles is required")
        @Size(max = 100, message = "roles must contain at most 100 entries")
        @Valid
        List<PositionRoleRequest> roles) {}
