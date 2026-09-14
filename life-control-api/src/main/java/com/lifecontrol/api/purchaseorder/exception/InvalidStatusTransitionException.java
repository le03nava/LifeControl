package com.lifecontrol.api.purchaseorder.exception;

import com.lifecontrol.api.exception.ConflictException;

public class InvalidStatusTransitionException extends ConflictException {

    public InvalidStatusTransitionException(String from, String to) {
        super("Invalid status transition: " + from + " → " + to);
    }
}
