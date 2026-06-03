package com.lifecontrol.api.paymentmethod.exception;

public class DuplicatePaymentMethodException extends RuntimeException {

    public DuplicatePaymentMethodException(String name) {
        super("Payment method with name '" + name + "' already exists");
    }
}
