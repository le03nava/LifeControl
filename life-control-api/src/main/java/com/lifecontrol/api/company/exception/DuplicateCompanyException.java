package com.lifecontrol.api.company.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateCompanyException extends DuplicateResourceException {

    public DuplicateCompanyException(String message) {
        super(message);
    }
}