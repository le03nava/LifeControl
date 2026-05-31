package com.lifecontrol.api.product.exception;

public class DuplicateProductException extends RuntimeException {

    public DuplicateProductException(String message) {
        super(message);
    }
}
