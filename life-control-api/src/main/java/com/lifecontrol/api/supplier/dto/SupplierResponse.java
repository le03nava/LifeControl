package com.lifecontrol.api.supplier.dto;

import com.lifecontrol.api.common.address.dto.AddressResponse;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read model of a supplier.
 *
 * <p>{@code companyId} is the company that owns the supplier, and it is also the scope the resource is
 * addressed through: {@code /api/companies/{companyId}/suppliers}. It is reported so a caller can prove
 * the owner it is acting under, not so it can discover one — a supplier of another company is never
 * returned by any endpoint of this resource.</p>
 */
public record SupplierResponse(
        UUID id,
        String supplierName,
        String razonSocial,
        String rfc,
        String email,
        String phoneNumber,
        String internalNumber,
        AddressResponse address,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        UUID companyId) {}
