package com.lifecontrol.api.store.dto;

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
    // Address fields (flattened)
    UUID addressId,
    String street,
    String streetNumber,
    String internalNumber,
    String neighborhood,
    String zipCode,
    String city,
    String state,
    UUID countryId,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
