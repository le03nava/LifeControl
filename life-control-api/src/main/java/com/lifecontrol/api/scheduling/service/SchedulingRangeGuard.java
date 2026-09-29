package com.lifecontrol.api.scheduling.service;

import com.lifecontrol.api.scheduling.exception.InvalidSchedulingRangeException;
import java.time.LocalDateTime;

/**
 * The one range bound every scheduling range read shares (D32): {@code to} strictly after
 * {@code from}, and a span of at most {@value #MAX_RANGE_DAYS} days.
 *
 * <p>The inclusive-end rule mirrors {@code SchedulingSlotService.validateRange} exactly: the cap is
 * expressed as an inclusive end instant, so a range of exactly {@value #MAX_RANGE_DAYS} days is
 * accepted and the first instant beyond it is rejected.</p>
 *
 * <p>W3b's slot-scoped {@code InvalidSchedulingSlotRangeException} is untouched; it stays on the
 * materializing {@code GET /slots} read. The coexistence is declared as gap <b>G18</b> on
 * {@link InvalidSchedulingRangeException}.</p>
 */
final class SchedulingRangeGuard {

    /** {@code MAX_RANGE_DAYS} mirrors the materialization cap so every range read is bounded alike. */
    static final long MAX_RANGE_DAYS = 90;

    private SchedulingRangeGuard() {}

    /**
     * Rejects a range that cannot be read: an empty or inverted one, and one wider than the cap.
     *
     * @throws InvalidSchedulingRangeException when {@code to} is not strictly after {@code from} or
     *     the span exceeds {@value #MAX_RANGE_DAYS} days (400)
     */
    static void validate(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new InvalidSchedulingRangeException("to must be after from");
        }
        if (to.isAfter(from.plusDays(MAX_RANGE_DAYS))) {
            throw new InvalidSchedulingRangeException("the range must not exceed " + MAX_RANGE_DAYS + " days");
        }
    }
}
