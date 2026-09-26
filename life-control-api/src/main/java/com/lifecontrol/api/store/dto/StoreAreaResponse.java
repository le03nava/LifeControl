package com.lifecontrol.api.store.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One store area.
 *
 * <p>{@code version} is the entity's optimistic-locking version. It always travels back to the
 * client so a caller can echo it in a later {@link UpdateStoreAreaRequest} and detect a lost
 * update.</p>
 */
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
        LocalDateTime updatedAt,
        long version) {}
