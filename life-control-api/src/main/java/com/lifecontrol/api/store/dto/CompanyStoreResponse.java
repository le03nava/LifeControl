package com.lifecontrol.api.store.dto;

import com.lifecontrol.api.common.address.dto.AddressResponse;

import java.time.LocalDateTime;
import java.util.UUID;

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
    LocalDateTime updatedAt
) {}
