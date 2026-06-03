package com.lifecontrol.api.status.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record StatusTypeResponse(
    UUID id,
    String statusTypeName,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
