package com.lifecontrol.api.store.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class CompanyStoreNotFoundException extends ResourceNotFoundException {

    public CompanyStoreNotFoundException(UUID id) {
        super("Store not found with id: " + id);
    }

    public CompanyStoreNotFoundException(String message) {
        super(message);
    }
}
