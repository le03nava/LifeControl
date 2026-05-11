package com.lifecontrol.api.company.exception;

public class DuplicateCompanyCountryException extends RuntimeException {

    public DuplicateCompanyCountryException(String countryCode) {
        super("The company already has a relationship with country: " + countryCode);
    }
}
