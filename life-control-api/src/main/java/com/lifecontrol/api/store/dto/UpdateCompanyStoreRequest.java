package com.lifecontrol.api.store.dto;

import com.lifecontrol.api.common.address.dto.AddressRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a company store. Every field is optional: {@code null} means "leave
 * unchanged".
 *
 * <p>{@code storeName} keeps the same upper bound as creation and rejects blank values when
 * provided: {@code @Size(min = 1, max = 255)} treats {@code null} as valid but a blank string as a
 * validation error (400).</p>
 */
public record UpdateCompanyStoreRequest(
        @Size(min = 1, max = 255, message = "storeName must not be blank")
        String storeName,

        @Email @Size(max = 255) String email,
        @Size(max = 50) String phoneNumber,
        @Valid AddressRequest address) {}
