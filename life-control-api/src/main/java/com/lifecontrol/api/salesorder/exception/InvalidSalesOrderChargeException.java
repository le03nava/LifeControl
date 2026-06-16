package com.lifecontrol.api.salesorder.exception;

import java.util.UUID;

public class InvalidSalesOrderChargeException extends RuntimeException {

    public InvalidSalesOrderChargeException(UUID orderId, String currentStatus) {
        super("Cannot charge sales order " + orderId + ": current status is " + currentStatus + ", expected Pending");
    }
}
