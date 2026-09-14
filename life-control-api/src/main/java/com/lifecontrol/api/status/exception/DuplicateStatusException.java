package com.lifecontrol.api.status.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateStatusException extends DuplicateResourceException {

    public DuplicateStatusException(String name) {
        super("Status with name '" + name + "' already exists for this status type");
    }
}
