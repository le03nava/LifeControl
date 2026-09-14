package com.lifecontrol.api.company.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;

public class CompanyRegionNotFoundException extends ResourceNotFoundException {

    public CompanyRegionNotFoundException(String message) {
        super(message);
    }
}
