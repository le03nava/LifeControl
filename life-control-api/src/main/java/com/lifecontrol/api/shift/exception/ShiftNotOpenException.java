package com.lifecontrol.api.shift.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

public class ShiftNotOpenException extends ConflictException {

    public ShiftNotOpenException(UUID shiftId, String currentStatus) {
        super("Shift " + shiftId + " is not open. Current status: " + currentStatus);
    }
}
