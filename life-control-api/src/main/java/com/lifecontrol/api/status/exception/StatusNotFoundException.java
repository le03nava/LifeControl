package com.lifecontrol.api.status.exception;

import java.util.UUID;

public class StatusNotFoundException extends RuntimeException {

    public StatusNotFoundException(UUID id) {
        super("Status not found with id: " + id);
    }

    public StatusNotFoundException(String message) {
        super(message);
    }
}
