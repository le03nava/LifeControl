package com.lifecontrol.api.scheduling.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * Raised when the slot a booking or reschedule names does not exist. Extends
 * {@link ResourceNotFoundException} so the shared {@code GlobalExceptionHandler} maps it to
 * <b>404 Not Found</b>, distinct from the 409 of a slot that exists but cannot be booked.
 */
public class SchedulingSlotNotFoundException extends ResourceNotFoundException {

    public SchedulingSlotNotFoundException(UUID id) {
        super("Scheduling slot not found with id: " + id);
    }
}
