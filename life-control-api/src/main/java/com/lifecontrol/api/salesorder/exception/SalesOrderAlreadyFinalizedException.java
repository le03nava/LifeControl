package com.lifecontrol.api.salesorder.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

public class SalesOrderAlreadyFinalizedException extends ConflictException {

    public SalesOrderAlreadyFinalizedException(UUID orderId, String currentStatus) {
        super("Sales order " + orderId + " is already " + currentStatus + " and cannot be modified");
    }
}
