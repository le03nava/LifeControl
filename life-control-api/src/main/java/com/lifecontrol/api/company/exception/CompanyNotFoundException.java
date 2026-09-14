package com.lifecontrol.api.company.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;

public class CompanyNotFoundException extends ResourceNotFoundException {

    public CompanyNotFoundException(java.util.UUID id) {
        super("Company not found with id: " + id);
    }
}
