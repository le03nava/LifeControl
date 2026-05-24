package com.lifecontrol.api.company.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CompanyRegionResponse(
    UUID id,
    UUID companyCountryId,
    UUID companyId,
    UUID countryId,
    String regionCode,
    String regionName,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
