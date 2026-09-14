package com.lifecontrol.api.paymentmethod.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class PaymentMethodNotFoundException extends ResourceNotFoundException {

    public PaymentMethodNotFoundException(UUID id) {
        super("Payment method not found with id: " + id);
    }
}
