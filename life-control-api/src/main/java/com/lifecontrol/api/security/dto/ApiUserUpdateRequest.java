package com.lifecontrol.api.security.dto;

import jakarta.validation.constraints.Size;

public record ApiUserUpdateRequest(
    @Size(max = 100, message = "Name must not exceed 100 characters")
    String name,

    @Size(max = 100, message = "Lastname must not exceed 100 characters")
    String lastname,

    @Size(max = 50, message = "Phone must not exceed 50 characters")
    String phone,

    Boolean enabled
) {}