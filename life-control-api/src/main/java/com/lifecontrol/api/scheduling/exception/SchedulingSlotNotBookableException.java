package com.lifecontrol.api.scheduling.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * Raised when a slot exists but cannot take the booking: it is disabled, or its {@code booked} count
 * already reached its {@code capacity} (D28). Both cases are the same client mistake — the seat is
 * not available — so they share one exception and both answer <b>409 Conflict</b> through
 * {@link ConflictException}, with a message that names the reason so the client can react (pick
 * another slot, or wait).
 */
public class SchedulingSlotNotBookableException extends ConflictException {

    private SchedulingSlotNotBookableException(String message) {
        super(message);
    }

    /** The slot is disabled and takes no bookings. */
    public static SchedulingSlotNotBookableException disabled(UUID slotId) {
        return new SchedulingSlotNotBookableException(
                "Scheduling slot " + slotId + " is not bookable: the slot is disabled");
    }

    /** The slot has every seat taken. */
    public static SchedulingSlotNotBookableException full(UUID slotId, int booked, int capacity) {
        return new SchedulingSlotNotBookableException("Scheduling slot " + slotId
                + " is not bookable: it is full (booked " + booked + " of capacity " + capacity + ")");
    }

    /** The slot belongs to an activity other than the appointment's. */
    public static SchedulingSlotNotBookableException foreignActivity(UUID slotId) {
        return new SchedulingSlotNotBookableException(
                "Scheduling slot " + slotId + " is not bookable: it belongs to a different activity");
    }
}
