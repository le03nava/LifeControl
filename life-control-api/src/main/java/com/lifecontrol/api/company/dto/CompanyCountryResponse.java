package com.lifecontrol.api.company.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CompanyCountryResponse(
    UUID id,
    UUID companyId,
    UUID countryId,
    String countryCode,
    String countryName,
    String localAlias,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
