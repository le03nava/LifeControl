package com.lifecontrol.api.salesorder.exception;

import java.util.UUID;

public class InvalidSalesOrderChargeException extends RuntimeException {

    public InvalidSalesOrderChargeException(UUID orderId, String currentStatus) {
        super("Cannot charge sales order " + orderId + ": current status is " + currentStatus + ", expected Pending");
    }

    /**
     * A charge request this service refuses for a reason of its own, where the message is the whole refusal.
     *
     * <p>Subclasses use it to inherit the {@code 400} the {@code GlobalExceptionHandler} maps this type to: a
     * request that states something the schema would refuse is the client's to fix, and answering it with the
     * handler's generic {@code 409} for a data constraint would name neither the payment nor the number.</p>
     */
    protected InvalidSalesOrderChargeException(String message) {
        super(message);
    }
}
