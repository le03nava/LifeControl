package com.lifecontrol.api.provisioning.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * 409 category for a second <b>open</b> access-provisioning task for the same employee.
 *
 * <p>The partial unique index {@code uq_access_provisioning_tasks_open} allows at most one
 * {@code PENDING}, {@code APPROVAL_PENDING}, {@code RUNNING} or {@code FAILED} task per employee.
 * The service refuses the duplicate before the insert through
 * {@code AccessProvisioningTaskRepository#existsByEmployeeIdAndStatusIn} so the caller gets a named
 * conflict, while the index stays the real race guard.</p>
 */
public class AccessProvisioningTaskAlreadyOpenException extends ConflictException {

    public AccessProvisioningTaskAlreadyOpenException(UUID employeeId) {
        super("Employee already has an open access provisioning task: " + employeeId);
    }
}
