package com.lifecontrol.api.store.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateCompanyStoreException extends DuplicateResourceException {

    public DuplicateCompanyStoreException(String message) {
        super(message);
    }
}
