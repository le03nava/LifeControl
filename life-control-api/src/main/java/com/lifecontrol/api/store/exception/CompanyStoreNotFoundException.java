package com.lifecontrol.api.store.exception;

import java.util.UUID;

public class CompanyStoreNotFoundException extends RuntimeException {

    public CompanyStoreNotFoundException(UUID id) {
        super("Store not found with id: " + id);
    }

    public CompanyStoreNotFoundException(String message) {
        super(message);
    }
}
