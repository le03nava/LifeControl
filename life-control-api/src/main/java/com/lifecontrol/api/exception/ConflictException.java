package com.lifecontrol.api.exception;

/**
 * Generic 409 category. Covers duplicate resources and invalid state
 * transitions that represent a conflict with the current server state.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
