package com.lifecontrol.api.status.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateStatusTypeException extends DuplicateResourceException {

    public DuplicateStatusTypeException(String name) {
        super("Status type with name '" + name + "' already exists");
    }
}
