package com.lifecontrol.api.paymentmethod.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PaymentMethodRequest(
        @NotBlank(message = "paymentMethodName is required") @Size(max = 100)
        String paymentMethodName,

        @NotBlank(message = "paymentMethodShortName is required") @Size(max = 50)
        String paymentMethodShortName,

        Boolean enabled,

        Boolean isCash) {

    public PaymentMethodRequest {
        if (enabled == null) {
            enabled = true;
        }
        // Defaults to the column's own default (`is_cash BOOLEAN NOT NULL DEFAULT false`, V83): a request
        // that does not mention cash creates a method that is not cash, which is the safe direction.
        if (isCash == null) {
            isCash = false;
        }
    }
}
