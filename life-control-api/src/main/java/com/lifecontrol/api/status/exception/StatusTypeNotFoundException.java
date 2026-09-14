package com.lifecontrol.api.status.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class StatusTypeNotFoundException extends ResourceNotFoundException {

    public StatusTypeNotFoundException(UUID id) {
        super("Status type not found with id: " + id);
    }
}
