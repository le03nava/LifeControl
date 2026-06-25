package com.lifecontrol.api.common.address.dto;

import java.util.UUID;

public record AddressResponse(
    UUID id,
    String street,
    String streetNumber,
    String internalNumber,
    String neighborhood,
    String zipCode,
    String city,
    String state,
    UUID countryId
) {}
