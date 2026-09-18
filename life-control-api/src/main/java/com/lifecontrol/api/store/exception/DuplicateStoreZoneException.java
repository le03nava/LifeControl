package com.lifecontrol.api.store.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateStoreZoneException extends DuplicateResourceException {

    public DuplicateStoreZoneException(String message) {
        super(message);
    }
}
