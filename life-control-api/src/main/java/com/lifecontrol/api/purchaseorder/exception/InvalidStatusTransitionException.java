package com.lifecontrol.api.purchaseorder.exception;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(String from, String to) {
        super("Invalid status transition: " + from + " → " + to);
    }
}
