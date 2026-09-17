package com.lifecontrol.api.store.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record StoreZoneResponse(
        UUID id,
        UUID storeAreaId,
        UUID companyStoreId,
        UUID companyId,
        UUID companyCountryId,
        UUID regionId,
        UUID zoneId,
        String zoneCode,
        String zoneName,
        String description,
        Integer displayOrder,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
