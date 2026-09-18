package com.lifecontrol.api.store.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record StoreLocationResponse(
        UUID id,
        UUID storeZoneId,
        UUID storeAreaId,
        UUID companyStoreId,
        UUID companyId,
        UUID companyCountryId,
        UUID regionId,
        UUID zoneId,
        String locationCode,
        String locationName,
        String description,
        Integer displayOrder,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
