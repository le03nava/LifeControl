package com.lifecontrol.api.company.dto;

import java.time.LocalDateTime;

public record CompanyResponse(
    Integer companyId,
    String companyKey,
    String companyName,
    Integer tipoPersonaId,
    String razonSocial,
    String rfc,
    String phone,
    String email,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}