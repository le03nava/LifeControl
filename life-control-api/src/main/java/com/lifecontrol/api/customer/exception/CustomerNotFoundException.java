package com.lifecontrol.api.customer.exception;

import java.util.UUID;

public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(UUID id) {
        super("Customer not found with id: " + id);
    }
}
