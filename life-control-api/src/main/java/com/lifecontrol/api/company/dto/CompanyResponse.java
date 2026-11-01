package com.lifecontrol.api.company.dto;

import com.lifecontrol.api.common.address.dto.AddressResponse;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The administration read model of a company.
 *
 * <p>{@code currency} is the company's operating currency (ADR-0002 decision 2, D-c): the ISO 4217 code
 * this tenant's documents are stated in. It is read-only here because it is stated when the company is
 * created; the field exists so a money screen never has to guess the code the way the Angular POS does
 * today with {@code DEFAULT_CURRENCY_CODE}.</p>
 */
public record CompanyResponse(
        UUID id,
        String companyKey,
        String companyName,
        Integer tipoPersonaId,
        String razonSocial,
        String rfc,
        String phone,
        String email,
        String emailDomain,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        AddressResponse address,
        String currency) {}
