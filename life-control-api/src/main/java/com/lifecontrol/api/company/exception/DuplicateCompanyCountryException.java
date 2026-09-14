package com.lifecontrol.api.company.exception;

import com.lifecontrol.api.exception.DuplicateResourceException;

public class DuplicateCompanyCountryException extends DuplicateResourceException {

    public DuplicateCompanyCountryException(String countryCode) {
        super("The company already has a relationship with country: " + countryCode);
    }
}
