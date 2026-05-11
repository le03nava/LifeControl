package com.lifecontrol.api.country.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CountryResponse(
    UUID id,
    String countryCode,
    String countryName,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
