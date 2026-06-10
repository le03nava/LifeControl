package com.lifecontrol.api.customer.dto;

import jakarta.validation.constraints.NotBlank;

public record CustomerRequest(

    @NotBlank(message = "name is required")
    String name,

    String email,

    String phone,

    String rfc,

    @NotBlank(message = "salesChannel is required")
    String salesChannel,

    Boolean enabled

) {
    public CustomerRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
