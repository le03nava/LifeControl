package com.lifecontrol.api.product.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateProductException extends DuplicateResourceException {

    public DuplicateProductException(String message) {
        super(message);
    }
}
