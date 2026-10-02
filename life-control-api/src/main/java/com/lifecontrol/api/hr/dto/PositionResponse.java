package com.lifecontrol.api.hr.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read model of a company-scoped position.
 *
 * <p>Following the catalog convention, this carries ids only: the department and the reporting
 * position are exposed as {@code departmentId}/{@code reportsToPositionId}, never as parent names.
 * {@code companyId} is derived through the department, which is where the position inherits its
 * company (decision T7).</p>
 */
public record PositionResponse(
        UUID id,
        UUID companyId,
        UUID departmentId,
        String positionCode,
        String positionName,
        String description,
        UUID reportsToPositionId,
        Integer displayOrder,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
