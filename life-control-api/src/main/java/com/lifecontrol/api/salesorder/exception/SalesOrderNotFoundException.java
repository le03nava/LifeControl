package com.lifecontrol.api.salesorder.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class SalesOrderNotFoundException extends ResourceNotFoundException {

    public SalesOrderNotFoundException(UUID id) {
        super("Sales order not found with id: " + id);
    }
}
