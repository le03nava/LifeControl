package com.lifecontrol.api.hr.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * 404 category for a department that does not exist or does not belong to the requested company.
 *
 * <p>The service always looks a department up through a company-scoped query, so a row owned by
 * another company resolves to this same not-found instead of a 200.</p>
 */
public class DepartmentNotFoundException extends ResourceNotFoundException {

    public DepartmentNotFoundException(UUID id) {
        super("Department not found with id: " + id);
    }
}
