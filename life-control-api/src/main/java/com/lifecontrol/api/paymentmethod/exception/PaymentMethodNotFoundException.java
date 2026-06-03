package com.lifecontrol.api.paymentmethod.exception;

import java.util.UUID;

public class PaymentMethodNotFoundException extends RuntimeException {

    public PaymentMethodNotFoundException(UUID id) {
        super("Payment method not found with id: " + id);
    }
}
