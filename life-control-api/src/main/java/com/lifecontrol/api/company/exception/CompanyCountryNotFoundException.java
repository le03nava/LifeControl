package com.lifecontrol.api.company.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

public class CompanyCountryNotFoundException extends ResourceNotFoundException {

    public CompanyCountryNotFoundException(UUID id) {
        super("Company-country relation not found with id: " + id);
    }
}
