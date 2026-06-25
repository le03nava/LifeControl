package com.lifecontrol.api.store.dto;

import com.lifecontrol.api.common.address.dto.AddressRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCompanyStoreRequest(
    @NotBlank(message = "storeName es requerido")
    @Size(max = 255)
    String storeName,

    @Email
    @Size(max = 255)
    String email,

    @Size(max = 50)
    String phoneNumber,

    AddressRequest address
) {}
