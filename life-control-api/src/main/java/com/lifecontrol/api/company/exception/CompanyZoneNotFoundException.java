package com.lifecontrol.api.company.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;

public class CompanyZoneNotFoundException extends ResourceNotFoundException {

    public CompanyZoneNotFoundException(String message) {
        super(message);
    }
}
