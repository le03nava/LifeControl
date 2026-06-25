package com.lifecontrol.api.store.dto;

import com.lifecontrol.api.common.address.dto.AddressRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateCompanyStoreRequest(
    @Size(max = 255)
    String storeName,

    @Email
    @Size(max = 255)
    String email,

    @Size(max = 50)
    String phoneNumber,

    AddressRequest address
) {}
