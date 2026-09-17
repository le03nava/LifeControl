package com.lifecontrol.api.store.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateStoreAreaException extends DuplicateResourceException {

    public DuplicateStoreAreaException(String message) {
        super(message);
    }
}
