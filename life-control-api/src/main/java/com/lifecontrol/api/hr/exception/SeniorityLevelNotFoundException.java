package com.lifecontrol.api.hr.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/** 404 category for a seniority level that does not exist. */
public class SeniorityLevelNotFoundException extends ResourceNotFoundException {

    public SeniorityLevelNotFoundException(UUID id) {
        super("Seniority level not found with id: " + id);
    }
}
