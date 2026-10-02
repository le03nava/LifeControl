package com.lifecontrol.api.hr.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Read model of a company-scoped department. */
public record DepartmentResponse(
        UUID id,
        UUID companyId,
        String departmentCode,
        String departmentName,
        String description,
        Integer displayOrder,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
