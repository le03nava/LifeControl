package com.lifecontrol.api.country.exception;

public class DuplicateCountryException extends RuntimeException {

    public DuplicateCountryException(String message) {
        super(message);
    }
}
