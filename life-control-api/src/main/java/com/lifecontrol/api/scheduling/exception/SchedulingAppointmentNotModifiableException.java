package com.lifecontrol.api.scheduling.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * Raised when a lifecycle write is asked to move an appointment whose status is terminal in the
 * transition map ({@code Completed}, {@code Cancelled}, {@code NoShow} or an unknown status name),
 * so it cannot be rescheduled (D29). Changability is terminality, not capacity: {@code Completed}
 * still holds capacity yet is final, and a {@code Cancelled}/{@code NoShow} appointment already
 * released its seat, so moving either one would rewrite what happened or take room it does not hold.
 * Extends {@link ConflictException}, so the shared {@code GlobalExceptionHandler} maps it to
 * <b>409 Conflict</b>.
 */
public class SchedulingAppointmentNotModifiableException extends ConflictException {

    public SchedulingAppointmentNotModifiableException(UUID id, String statusName) {
        super("Scheduling appointment " + id + " cannot be modified while in status '" + statusName + "'");
    }
}
