package com.lifecontrol.api.company.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateCompanyZoneException extends DuplicateResourceException {

    public DuplicateCompanyZoneException(String message) {
        super(message);
    }
}
