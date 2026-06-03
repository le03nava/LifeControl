package com.lifecontrol.api.status.exception;

public class DuplicateStatusException extends RuntimeException {

    public DuplicateStatusException(String name) {
        super("Status with name '" + name + "' already exists for this status type");
    }
}
