package com.lifecontrol.api.store.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One store location.
 *
 * <p>{@code version} is the entity's optimistic-locking version. It always travels back to the
 * client so a caller can echo it in a later {@link UpdateStoreLocationRequest} and detect a lost
 * update.</p>
 */
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
        LocalDateTime updatedAt,
        long version) {}
