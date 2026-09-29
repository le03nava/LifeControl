package com.lifecontrol.api.scheduling.exception;

/**
 * Raised when a scheduling range read is impossible: {@code to} is not strictly after {@code from},
 * or the span exceeds the shared 90-day cap (D32). Extends {@link IllegalArgumentException}, so the
 * shared {@code GlobalExceptionHandler} maps it to <b>400 Bad Request</b> with no handler change.
 *
 * <p>This exception deliberately coexists with W3b's {@code InvalidSchedulingSlotRangeException},
 * which stays <b>slot-scoped</b> to {@code GET /api/scheduling/slots}: both mean "the request is not
 * a usable {@code [from, to)} range" and both answer 400, but they are separate contracts. Merging or
 * renaming them would change a contract W3b already shipped and reviewed, so unifying the two is a
 * separate work unit rather than a rider on W4b. Recorded as gap <b>G18</b>.</p>
 */
public class InvalidSchedulingRangeException extends IllegalArgumentException {

    public InvalidSchedulingRangeException(String message) {
        super(message);
    }
}
