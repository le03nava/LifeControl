package com.lifecontrol.api.security.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ApiUserResponse(
    UUID id,
    String username,
    String email,
    String name,
    String lastname,
    String phone,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}