package com.lifecontrol.api.scheduling.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

/**
 * Raised when an activity name already exists in the same store. Extends
 * {@link DuplicateResourceException} so the shared {@code GlobalExceptionHandler} maps it to
 * <b>409 Conflict</b>.
 */
public class DuplicateSchedulingActivityException extends DuplicateResourceException {

    public DuplicateSchedulingActivityException(String message) {
        super(message);
    }
}
