package com.lifecontrol.api.store.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateStoreLocationException extends DuplicateResourceException {

    public DuplicateStoreLocationException(String message) {
        super(message);
    }
}
