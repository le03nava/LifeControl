package com.lifecontrol.api.hr.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read model of a position's role template.
 *
 * <p>Following the catalog convention, this carries ids only plus the role name. {@code enabled} is
 * part of the contract: the read returns disabled rows too, so the client can tell an explicitly
 * cleared role from one that was never configured (record T20). The rows carry the {@code Auditable}
 * timestamps.</p>
 */
public record PositionRoleResponse(
        UUID id, UUID positionId, String roleName, Boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt) {}
