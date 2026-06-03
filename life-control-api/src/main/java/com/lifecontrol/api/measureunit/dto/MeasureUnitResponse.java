package com.lifecontrol.api.measureunit.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record MeasureUnitResponse(
    UUID id,
    String measureUnitName,
    String measureUnitShortName,
    String unitType,
    String satCode,
    String description,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
