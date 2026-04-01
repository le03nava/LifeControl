package com.lifecontrol.api.company.exception;

public class DuplicateCompanyException extends RuntimeException {

    public DuplicateCompanyException(String message) {
        super(message);
    }
}