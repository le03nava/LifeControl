package com.lifecontrol.api.status.exception;

public class DuplicateStatusTypeException extends RuntimeException {

    public DuplicateStatusTypeException(String name) {
        super("Status type with name '" + name + "' already exists");
    }
}
