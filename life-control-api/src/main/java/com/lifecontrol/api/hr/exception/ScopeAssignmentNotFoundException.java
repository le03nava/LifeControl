package com.lifecontrol.api.hr.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * 404 category for a scope assignment that does not exist or does not belong to the requested
 * employee.
 *
 * <p>The service always looks an assignment up through an employee-scoped query, and the employee
 * itself through a company-scoped one, so a row owned by another employee or another company resolves
 * to this same not-found instead of a 200.</p>
 */
public class ScopeAssignmentNotFoundException extends ResourceNotFoundException {

    public ScopeAssignmentNotFoundException(UUID id) {
        super("Scope assignment not found with id: " + id);
    }
}
