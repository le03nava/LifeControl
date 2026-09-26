package com.lifecontrol.api.store.dto;

import com.lifecontrol.api.common.address.dto.AddressResponse;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One company store.
 *
 * <p>{@code version} is the entity's optimistic-locking version. It always travels back to the
 * client so a caller can echo it in a later {@link UpdateCompanyStoreRequest} and detect a lost
 * update.</p>
 */
public record CompanyStoreResponse(
        UUID id,
        UUID companyId,
        UUID companyCountryId,
        UUID regionId,
        UUID zoneId,
        String storeName,
        String email,
        String phoneNumber,
        AddressResponse address,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version) {}
