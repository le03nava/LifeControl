package com.lifecontrol.api.accounting.exception;

import java.time.LocalDate;

/**
 * 400 category for a report range whose end is before its start.
 *
 * <p>Extends {@link IllegalArgumentException}, which {@code GlobalExceptionHandler} answers with a 400 carrying
 * the message — the shape {@code InvalidTaxRateSupersessionException} already uses for a malformed request that
 * contradicts itself rather than a stored row.</p>
 *
 * <p><b>Why the message names both dates.</b> "Invalid range" leaves the caller to guess which of the two
 * parameters it got wrong, and a date range has exactly two. The refusal is also the only way to tell a caller
 * that an empty report is a legitimately empty report and not this: {@code S18} makes an empty result a 200, so
 * a refused range must be unambiguous.</p>
 */
public class InvalidAccountingRangeException extends IllegalArgumentException {

    public InvalidAccountingRangeException(LocalDate from, LocalDate to) {
        super("The end of the range must not be before its start: from " + from + " to " + to
                + ". Both bounds are inclusive business dates compared against the entry's own date, and an"
                + " inverted range would silently report an empty book instead of refusing the request.");
    }
}
