package com.lifecontrol.api.status.exception;

import java.util.UUID;

public class StatusTypeNotFoundException extends RuntimeException {

    public StatusTypeNotFoundException(UUID id) {
        super("Status type not found with id: " + id);
    }
}
