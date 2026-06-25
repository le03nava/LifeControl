package com.lifecontrol.api.supplier.dto;

import com.lifecontrol.api.common.address.dto.AddressResponse;
import java.time.LocalDateTime;
import java.util.UUID;

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
    LocalDateTime updatedAt
) {}
