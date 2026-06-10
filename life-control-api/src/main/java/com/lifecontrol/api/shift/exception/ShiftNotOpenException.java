package com.lifecontrol.api.shift.exception;

import java.util.UUID;

public class ShiftNotOpenException extends RuntimeException {

    public ShiftNotOpenException(UUID shiftId, String currentStatus) {
        super("Shift " + shiftId + " is not open. Current status: " + currentStatus);
    }
}
