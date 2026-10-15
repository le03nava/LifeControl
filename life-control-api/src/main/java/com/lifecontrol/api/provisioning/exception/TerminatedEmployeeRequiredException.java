package com.lifecontrol.api.provisioning.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * 409 category for an explicit revocation refused because the employee is <b>not</b> {@code Terminated}
 * (record T101).
 *
 * <p>The decision it encodes is that a {@code DEACTIVATE} intent only exists for a truth of no access:
 * the outcome it produces is the account disabled and the template's roles and the five
 * {@code company_*} claims removed, while the employee's own required set is derived from the current
 * contract — so for an employee who is not revoked the very next fact of that same employee would grant
 * back exactly what the revocation removed. A revocation that undoes itself is not a revocation, and
 * the endpoint that asks for it is where the mismatch gets its name.</p>
 *
 * <p>It mirrors {@link TerminatedEmployeeRefusedException} in the opposite direction (record T84:
 * {@code apply} on a {@code Terminated} employee): same helper ({@code EmployeeStatuses.isTerminated}),
 * so the surface and the worker cannot disagree about who is revoked, and the same thin semantic alias
 * of {@link ConflictException} — the handler resolves it by inheritance, the refusal reaches the wire as
 * a named <b>409</b> with its message intact (record T86), and no new handler is added.</p>
 */
public class TerminatedEmployeeRequiredException extends ConflictException {

    public TerminatedEmployeeRequiredException(UUID employeeId) {
        super("Revocation requires a Terminated employee: employee " + employeeId
                + " is not revoked, so there is no access to remove");
    }
}
