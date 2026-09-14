package com.lifecontrol.api.shift.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class ShiftNotFoundException extends ResourceNotFoundException {

    public ShiftNotFoundException(UUID id) {
        super("Shift not found with id: " + id);
    }
}
