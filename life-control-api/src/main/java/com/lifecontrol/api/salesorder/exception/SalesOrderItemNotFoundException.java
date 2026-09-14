package com.lifecontrol.api.salesorder.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class SalesOrderItemNotFoundException extends ResourceNotFoundException {

    public SalesOrderItemNotFoundException(UUID id) {
        super("Sales order item not found with id: " + id);
    }
}
