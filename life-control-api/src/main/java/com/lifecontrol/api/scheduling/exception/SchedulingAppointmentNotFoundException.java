package com.lifecontrol.api.scheduling.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * Raised when a scheduling appointment does not exist. Extends {@link ResourceNotFoundException} so
 * the shared {@code GlobalExceptionHandler} maps it to <b>404 Not Found</b>.
 */
public class SchedulingAppointmentNotFoundException extends ResourceNotFoundException {

    public SchedulingAppointmentNotFoundException(UUID id) {
        super("Scheduling appointment not found with id: " + id);
    }
}
