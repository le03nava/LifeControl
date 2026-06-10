package com.lifecontrol.api.profile.dto;

import java.util.UUID;

public record ProfileResponse(
    String keycloakUserId,
    String username,
    String email,
    String firstName,
    String lastName,
    UUID companyCountryId,
    UUID companyId,
    UUID companyRegionId,
    UUID companyZoneId,
    UUID companyStoreId
) {}
