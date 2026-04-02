package com.lifecontrol.api.company.exception;

public class CompanyNotFoundException extends RuntimeException {

    public CompanyNotFoundException(java.util.UUID id) {
        super("Company not found with id: " + id);
    }
}
