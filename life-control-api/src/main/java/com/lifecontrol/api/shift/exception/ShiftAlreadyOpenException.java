package com.lifecontrol.api.shift.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

public class ShiftAlreadyOpenException extends ConflictException {

    public ShiftAlreadyOpenException(UUID storeId) {
        super("An open shift already exists for store: " + storeId);
    }
}
