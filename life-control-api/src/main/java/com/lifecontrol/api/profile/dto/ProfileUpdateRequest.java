package com.lifecontrol.api.profile.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ProfileUpdateRequest(
    @Size(max = 255) String firstName,
    @Size(max = 255) String lastName,
    @Email @Size(max = 255) String email,
    UUID companyCountryId,
    UUID companyId,
    UUID companyRegionId,
    UUID companyZoneId,
    UUID companyStoreId
) {}
