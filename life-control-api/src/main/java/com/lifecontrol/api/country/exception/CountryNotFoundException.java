package com.lifecontrol.api.country.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class CountryNotFoundException extends ResourceNotFoundException {

    public CountryNotFoundException(UUID id) {
        super("Country not found with id: " + id);
    }

    public CountryNotFoundException(String countryCode) {
        super("Country not found with code: " + countryCode);
    }
}
