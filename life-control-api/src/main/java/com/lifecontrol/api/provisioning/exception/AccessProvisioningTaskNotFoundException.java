package com.lifecontrol.api.provisioning.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * 404 category for an access-provisioning task that does not exist or does not belong to the
 * requested employee.
 *
 * <p>The service always looks a task up through an employee-scoped query, so a row owned by another
 * employee resolves to this same not-found instead of a 200 — the convention
 * {@code EmployeeRepository#findByIdAndCompanyId} and {@code ContractNotFoundException} state.</p>
 */
public class AccessProvisioningTaskNotFoundException extends ResourceNotFoundException {

    public AccessProvisioningTaskNotFoundException(UUID id) {
        super("Access provisioning task not found with id: " + id);
    }
}
