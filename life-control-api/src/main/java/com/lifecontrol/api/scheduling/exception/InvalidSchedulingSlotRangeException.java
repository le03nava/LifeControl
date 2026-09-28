package com.lifecontrol.api.scheduling.exception;

/**
 * Raised when the requested slot range is impossible: {@code to} is not strictly after {@code from},
 * or the span exceeds the 90-day cap that bounds materialization. Extends
 * {@link IllegalArgumentException}, so the shared {@code GlobalExceptionHandler} maps it to
 * <b>400 Bad Request</b> with no handler change.
 */
public class InvalidSchedulingSlotRangeException extends IllegalArgumentException {

    public InvalidSchedulingSlotRangeException(String message) {
        super(message);
    }
}
