package com.lifecontrol.api.country.exception;

import java.util.UUID;

public class CountryNotFoundException extends RuntimeException {

    public CountryNotFoundException(UUID id) {
        super("Country not found with id: " + id);
    }

    public CountryNotFoundException(String countryCode) {
        super("Country not found with code: " + countryCode);
    }
}
