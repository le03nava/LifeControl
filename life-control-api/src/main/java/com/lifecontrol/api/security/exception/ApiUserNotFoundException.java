package com.lifecontrol.api.security.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ApiUserNotFoundException extends RuntimeException {

    public ApiUserNotFoundException(String message) {
        super(message);
    }
}
