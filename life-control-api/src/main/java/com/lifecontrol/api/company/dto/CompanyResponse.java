package com.lifecontrol.api.company.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CompanyResponse(
    UUID id,
    String companyKey,
    String companyName,
    Integer tipoPersonaId,
    String razonSocial,
    String rfc,
    String phone,
    String email,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String street,
    String streetNumber,
    String internalNumber,
    String neighborhood,
    String zipCode,
    String city,
    String state,
    UUID countryId
) {}