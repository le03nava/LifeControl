package com.lifecontrol.api.company.exception;

import java.util.UUID;

public class CompanyCountryNotFoundException extends RuntimeException {

    public CompanyCountryNotFoundException(UUID id) {
        super("Company-country relation not found with id: " + id);
    }
}
