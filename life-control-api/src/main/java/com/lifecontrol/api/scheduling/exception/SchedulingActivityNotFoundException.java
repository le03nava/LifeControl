package com.lifecontrol.api.scheduling.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * Raised when a scheduling activity does not exist. Extends {@link ResourceNotFoundException} so the
 * shared {@code GlobalExceptionHandler} maps it to <b>404 Not Found</b>.
 */
public class SchedulingActivityNotFoundException extends ResourceNotFoundException {

    public SchedulingActivityNotFoundException(UUID id) {
        super("Scheduling activity not found with id: " + id);
    }
}
