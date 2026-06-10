package com.lifecontrol.api.shift.exception;

import java.util.UUID;

public class ShiftNotFoundException extends RuntimeException {

    public ShiftNotFoundException(UUID id) {
        super("Shift not found with id: " + id);
    }
}
