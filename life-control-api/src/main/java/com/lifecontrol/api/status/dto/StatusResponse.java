package com.lifecontrol.api.status.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record StatusResponse(
    UUID id,
    String statusName,
    UUID statusTypeId,
    String statusTypeName,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
