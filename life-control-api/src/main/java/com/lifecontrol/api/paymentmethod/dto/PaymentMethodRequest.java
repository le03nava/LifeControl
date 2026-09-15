package com.lifecontrol.api.paymentmethod.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PaymentMethodRequest(
        @NotBlank(message = "paymentMethodName is required") @Size(max = 100)
        String paymentMethodName,

        @NotBlank(message = "paymentMethodShortName is required") @Size(max = 50)
        String paymentMethodShortName,

        Boolean enabled) {

    public PaymentMethodRequest {
        if (enabled == null) {
            enabled = true;
        }
    }
}
