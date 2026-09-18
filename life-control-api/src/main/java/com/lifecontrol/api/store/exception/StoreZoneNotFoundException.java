package com.lifecontrol.api.store.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class StoreZoneNotFoundException extends ResourceNotFoundException {

    public StoreZoneNotFoundException(UUID id) {
        super("Store zone not found with id: " + id);
    }

    public StoreZoneNotFoundException(String message) {
        super(message);
    }
}
