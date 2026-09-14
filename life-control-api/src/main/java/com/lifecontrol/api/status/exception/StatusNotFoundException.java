package com.lifecontrol.api.status.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class StatusNotFoundException extends ResourceNotFoundException {

    public StatusNotFoundException(UUID id) {
        super("Status not found with id: " + id);
    }

    public StatusNotFoundException(String message) {
        super(message);
    }
}
