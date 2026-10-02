package com.lifecontrol.api.hr.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Read model of a seniority level. */
public record SeniorityLevelResponse(
        UUID id,
        String levelCode,
        String levelName,
        Integer rank,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
