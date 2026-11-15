package com.lifecontrol.api.customer.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One customer as the API answers it.
 *
 * <p>{@code legalName}, {@code regimenFiscal}, {@code fiscalStreet}, {@code fiscalZipCode}, {@code fiscalState},
 * {@code fiscalCountryId} and {@code defaultUsoCfdi} are the receiver's fiscal profile (S2 of
 * {@code odd/tasks/cfdi-fiscal-document.md}). All seven are absent together or stated together — the write path
 * refuses a partial profile — and a customer with none of them is a complete cash customer, which is why they
 * are nullable here rather than defaulted.</p>
 *
 * <p>{@code rfc} is carried beside them and is <b>not</b> part of the profile: a customer may have a fiscal
 * identifier and no profile yet, and the shape of the identifier is validated by {@code @ValidRFC} whether or
 * not a profile follows it.</p>
 */
public record CustomerResponse(
        UUID id,
        String name,
        String email,
        String phone,
        String rfc,
        String salesChannel,
        Boolean enabled,
        String legalName,
        String regimenFiscal,
        String fiscalStreet,
        String fiscalZipCode,
        String fiscalState,
        UUID fiscalCountryId,
        String defaultUsoCfdi,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
