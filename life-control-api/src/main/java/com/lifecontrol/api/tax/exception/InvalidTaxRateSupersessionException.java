package com.lifecontrol.api.tax.exception;

import java.time.LocalDate;

/**
 * 400 category for a supersession that does not move forward in time.
 *
 * <p>Extends {@link IllegalArgumentException}, which {@code GlobalExceptionHandler} answers with a 400 that
 * carries the message: this is a malformed request, not a conflict with a stored row. It is a named class
 * rather than a bare {@code IllegalArgumentException} so the rule is greppable and the sentence cannot drift
 * between the two call sites that could state it.</p>
 *
 * <p><b>Why the rule exists.</b> "Supersede" means "from this day, this rate instead"; a successor dated on or
 * before the row it replaces would claim to apply earlier than the statement it supersedes, and the resolution
 * would answer it for documents that were already issued. Backfilling history is a legitimate act and has its
 * own verb: {@code POST}, which appends without claiming to supersede anything.</p>
 */
public class InvalidTaxRateSupersessionException extends IllegalArgumentException {

    public InvalidTaxRateSupersessionException(LocalDate superseded, LocalDate successor) {
        super("A superseding rate must take effect after the rate it supersedes: " + successor + " is not after "
                + superseded);
    }
}
