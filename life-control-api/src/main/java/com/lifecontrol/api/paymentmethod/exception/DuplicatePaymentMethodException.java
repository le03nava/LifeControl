package com.lifecontrol.api.paymentmethod.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicatePaymentMethodException extends DuplicateResourceException {

    public DuplicatePaymentMethodException(String name) {
        super("Payment method with name '" + name + "' already exists");
    }
}
