package com.lifecontrol.api.store.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class StoreAreaNotFoundException extends ResourceNotFoundException {

    public StoreAreaNotFoundException(UUID id) {
        super("Store area not found with id: " + id);
    }

    public StoreAreaNotFoundException(String message) {
        super(message);
    }
}
