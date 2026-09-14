package com.lifecontrol.api.country.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateCountryException extends DuplicateResourceException {

    public DuplicateCountryException(String message) {
        super(message);
    }
}
