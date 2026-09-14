package com.lifecontrol.api.exception;

/**
 * Generic duplicate-resource category (409). Domain-specific duplicate
 * exceptions extend this class.
 */
public class DuplicateResourceException extends ConflictException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
