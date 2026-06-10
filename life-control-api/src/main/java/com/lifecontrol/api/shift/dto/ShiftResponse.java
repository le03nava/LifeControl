package com.lifecontrol.api.shift.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ShiftResponse(
    UUID id,
    UUID companyStoreId,
    String userId,
    LocalDateTime openedAt,
    LocalDateTime closedAt,
    String status,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
