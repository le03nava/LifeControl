package com.lifecontrol.api.store.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class StoreLocationNotFoundException extends ResourceNotFoundException {

    public StoreLocationNotFoundException(UUID id) {
        super("Store location not found with id: " + id);
    }

    public StoreLocationNotFoundException(String message) {
        super(message);
    }
}
