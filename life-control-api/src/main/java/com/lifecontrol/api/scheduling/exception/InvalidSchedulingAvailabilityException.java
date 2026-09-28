package com.lifecontrol.api.scheduling.exception;

/**
 * Raised when an availability window is internally impossible or overlaps another window of the same
 * weekday. Extends {@link IllegalArgumentException}, so the shared {@code GlobalExceptionHandler}
 * maps it to <b>400 Bad Request</b> with no handler change — the same category
 * {@code InvalidSalesOrderChargeException} uses only because the latter extends
 * {@code RuntimeException}.
 */
public class InvalidSchedulingAvailabilityException extends IllegalArgumentException {

    public InvalidSchedulingAvailabilityException(String message) {
        super(message);
    }
}
