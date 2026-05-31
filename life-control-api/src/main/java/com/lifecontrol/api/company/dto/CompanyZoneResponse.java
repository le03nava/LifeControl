package com.lifecontrol.api.company.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CompanyZoneResponse(
    UUID id,
    UUID companyRegionId,
    UUID companyCountryId,
    UUID companyId,
    UUID countryId,
    String zoneCode,
    String zoneName,
    String description,
    Integer displayOrder,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
