package com.lifecontrol.api.customer.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CustomerResponse(
    UUID id,
    String name,
    String email,
    String phone,
    String rfc,
    String salesChannel,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
