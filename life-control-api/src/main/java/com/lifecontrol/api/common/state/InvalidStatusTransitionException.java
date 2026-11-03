package com.lifecontrol.api.common.state;

import com.lifecontrol.api.exception.ConflictException;

/**
 * A state change the domain enum does not declare (ADR-0004 decision 2), answered as
 * {@code 409 Conflict}: the document exists and the request is refused because of its current state.
 *
 * <p>It lived in {@code purchaseorder.exception} while the transition sets were private maps inside the
 * two order services; the machine now belongs to the document families and not to one of them, and the
 * class moves with it. {@code SchedulingAppointmentService} keeps throwing it for the appointment
 * lifecycle, which ADR-0004 leaves out of scope by decision.</p>
 */
public class InvalidStatusTransitionException extends ConflictException {

    public InvalidStatusTransitionException(String from, String to) {
        super("Invalid status transition: " + from + " → " + to);
    }
}
