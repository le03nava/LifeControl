package com.lifecontrol.api.common.address.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AddressRequest(
    @Size(max = 255, message = "La calle no puede superar los {max} caracteres")
    String street,

    @Size(max = 20, message = "El número exterior no puede superar los {max} caracteres")
    String streetNumber,

    @Size(max = 20, message = "El número interior no puede superar los {max} caracteres")
    String internalNumber,

    @Size(max = 255, message = "La colonia no puede superar los {max} caracteres")
    String neighborhood,

    @Size(max = 20, message = "El código postal no puede superar los {max} caracteres")
    String zipCode,

    @Size(max = 255, message = "La ciudad no puede superar los {max} caracteres")
    String city,

    @Size(max = 255, message = "El estado no puede superar los {max} caracteres")
    String state,

    UUID countryId
) {}
