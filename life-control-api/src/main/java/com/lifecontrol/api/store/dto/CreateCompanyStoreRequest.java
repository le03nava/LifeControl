package com.lifecontrol.api.store.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateCompanyStoreRequest(
    @NotBlank(message = "storeName es requerido")
    @Size(max = 255)
    String storeName,

    @Email
    @Size(max = 255)
    String email,

    @Size(max = 50)
    String phoneNumber,

    // Address fields (all optional - address is nullable)
    @Size(max = 255)
    String street,

    @Size(max = 20)
    String streetNumber,

    @Size(max = 20)
    String internalNumber,

    @Size(max = 255)
    String neighborhood,

    @Size(max = 20)
    String zipCode,

    @Size(max = 255)
    String city,

    @Size(max = 255)
    String state,

    UUID countryId
) {}
