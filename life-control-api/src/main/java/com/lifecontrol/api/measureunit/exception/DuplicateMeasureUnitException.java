package com.lifecontrol.api.measureunit.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateMeasureUnitException extends DuplicateResourceException {

    public DuplicateMeasureUnitException(String message) {
        super(message);
    }
}
