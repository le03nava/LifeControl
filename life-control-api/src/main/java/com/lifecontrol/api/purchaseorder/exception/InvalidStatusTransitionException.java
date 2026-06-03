package com.lifecontrol.api.purchaseorder.exception;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(String from, String to) {
        super("Transición de estado inválida: " + from + " → " + to);
    }
}
