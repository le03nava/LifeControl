package com.lifecontrol.api.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApiUserRequest(
    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    String username,

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    String email,

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
    String password,

    @Size(max = 100, message = "Name must not exceed 100 characters")
    String name,

    @Size(max = 100, message = "Lastname must not exceed 100 characters")
    String lastname,

    @Size(max = 50, message = "Phone must not exceed 50 characters")
    String phone,

    Boolean enabled
) {}