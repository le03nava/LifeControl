package com.lifecontrol.api.company.dto;

import com.lifecontrol.api.common.address.dto.AddressResponse;

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
    AddressResponse address
) {}