package com.lifecontrol.api.hr.dto;

import jakarta.validation.constraints.NotNull;

/**
 * One role inside the full-set save request.
 *
 * <p>The item carries no {@code enabled} field on purpose: presence in the request list means the
 * role is enabled, and omission means it is disabled (record T20). Exposing a second way to say the
 * same thing would let a client submit an "enabled" item that the omission rule then clears.</p>
 *
 * <p>{@code roleName} is required and is validated twice by the service, both as a 400: it must be in
 * the frozen allowlist of {@code GrantablePositionRoles} (decision D7) and it must exist as a client
 * role of the configured application client (record T18, E15).</p>
 */
public record PositionRoleRequest(
        @NotNull(message = "roleName is required") String roleName) {}
