package com.lifecontrol.api.salesorder.exception;

import java.util.UUID;

public class SalesOrderAlreadyFinalizedException extends RuntimeException {

    public SalesOrderAlreadyFinalizedException(UUID orderId, String currentStatus) {
        super("Sales order " + orderId + " is already " + currentStatus + " and cannot be modified");
    }
}
