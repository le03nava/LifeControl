package com.lifecontrol.api.hr.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read model of a position's salary band.
 *
 * <p>Following the catalog convention, this carries ids only: the seniority level is exposed as
 * {@code seniorityLevelId} and never as a name. The client resolves level names from the existing
 * {@code /api/seniority-levels} endpoint.</p>
 *
 * <p>{@code enabled} is part of the contract: the read returns disabled rows too, so the client can
 * tell an explicitly cleared band from one that was never configured (decision D13).</p>
 */
public record PositionSalaryBandResponse(
        UUID id,
        UUID positionId,
        UUID seniorityLevelId,
        BigDecimal minimumSalary,
        BigDecimal maximumSalary,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
