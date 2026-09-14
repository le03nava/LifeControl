package com.lifecontrol.api.company.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateCompanyRegionException extends DuplicateResourceException {

    public DuplicateCompanyRegionException(String message) {
        super(message);
    }
}
