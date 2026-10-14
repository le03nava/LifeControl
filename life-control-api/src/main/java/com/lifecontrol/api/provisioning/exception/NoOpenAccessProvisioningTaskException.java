package com.lifecontrol.api.provisioning.exception;

import com.lifecontrol.api.exception.ResourceNotFoundException;
import java.util.UUID;

/**
 * 404 category for the operator's retry when the employee has <b>no open</b> access-provisioning task
 * (unit W5c, slice S2, record T87).
 *
 * <p>The retry route carries no {@code taskId}: the surface's own shape resolves the row from the
 * employee, so "there is nothing to retry" is a not-found on the employee's access state rather than
 * on an id the caller never sent. It is a <b>sibling</b> of
 * {@link AccessProvisioningTaskNotFoundException} and not a reuse of it: that class' message names a
 * task id, and reusing it here would report an id nobody asked about.</p>
 *
 * <p>An open task that is <b>not</b> {@code FAILED} is a different answer and is not this
 * exception's: that one comes from the state machine itself
 * ({@link InvalidTaskStatusTransitionException}, 409), so the surface never re-states the machine's
 * rule.</p>
 */
public class NoOpenAccessProvisioningTaskException extends ResourceNotFoundException {

    public NoOpenAccessProvisioningTaskException(UUID employeeId) {
        super("No open access provisioning task for employee " + employeeId);
    }
}
