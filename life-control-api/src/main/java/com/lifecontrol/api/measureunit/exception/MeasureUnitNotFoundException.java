package com.lifecontrol.api.measureunit.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class MeasureUnitNotFoundException extends ResourceNotFoundException {

    public MeasureUnitNotFoundException(UUID id) {
        super("Measure unit not found with id: " + id);
    }

    public MeasureUnitNotFoundException(String satCode) {
        super("Measure unit not found with SAT code: " + satCode);
    }
}
