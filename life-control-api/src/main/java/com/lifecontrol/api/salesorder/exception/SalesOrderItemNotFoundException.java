package com.lifecontrol.api.salesorder.exception;

import java.util.UUID;

public class SalesOrderItemNotFoundException extends RuntimeException {

    public SalesOrderItemNotFoundException(UUID id) {
        super("Sales order item not found with id: " + id);
    }
}
