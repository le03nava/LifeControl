package com.lifecontrol.api.supplier.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record SupplierResponse(
    UUID id,
    String supplierName,
    String razonSocial,
    String rfc,
    String email,
    String phoneNumber,
    String street,
    String streetNumber,
    String neighborhood,
    String zipCode,
    String city,
    String state,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
