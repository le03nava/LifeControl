package com.lifecontrol.api.customer.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class CustomerNotFoundException extends ResourceNotFoundException {

    public CustomerNotFoundException(UUID id) {
        super("Customer not found with id: " + id);
    }
}
