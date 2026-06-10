package com.lifecontrol.api.shift.exception;

import java.util.UUID;

public class ShiftAlreadyOpenException extends RuntimeException {

    public ShiftAlreadyOpenException(UUID storeId) {
        super("An open shift already exists for store: " + storeId);
    }
}
