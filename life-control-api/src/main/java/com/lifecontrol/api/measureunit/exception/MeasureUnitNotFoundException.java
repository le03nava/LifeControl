package com.lifecontrol.api.measureunit.exception;

import java.util.UUID;

public class MeasureUnitNotFoundException extends RuntimeException {

    public MeasureUnitNotFoundException(UUID id) {
        super("Measure unit not found with id: " + id);
    }

    public MeasureUnitNotFoundException(String satCode) {
        super("Measure unit not found with SAT code: " + satCode);
    }
}
