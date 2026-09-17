package com.lifecontrol.api.store.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record StoreAreaResponse(
        UUID id,
        UUID companyStoreId,
        UUID companyId,
        UUID companyCountryId,
        UUID regionId,
        UUID zoneId,
        String areaCode,
        String areaName,
        String description,
        Integer displayOrder,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
