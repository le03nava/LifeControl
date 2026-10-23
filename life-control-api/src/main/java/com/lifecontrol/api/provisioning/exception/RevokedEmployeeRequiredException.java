package com.lifecontrol.api.provisioning.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * 409 category for an explicit revocation refused because the employee is <b>not revoked</b> —
 * neither {@code Terminated} nor soft-deleted (records T101 and G41).
 *
 * <p>The decision it encodes is that a {@code DEACTIVATE} intent only exists for a truth of no access:
 * the outcome it produces is the account disabled and the template's roles and the five
 * {@code company_*} claims removed, while the employee's own required set is derived from the current
 * contract — so for an employee who still has access the very next fact of that same employee would
 * grant back exactly what the revocation removed. A revocation that undoes itself is not a
 * revocation, and the endpoint that asks for it is where the mismatch gets its name.</p>
 *
 * <p><b>The name states the rule the guard enforces.</b> The class was named after
 * {@code TerminatedEmployeeRequiredException} while {@code deactivate} required
 * {@code EmployeeStatuses.isTerminated}; the projection's single rule is
 * {@code EmployeeStatuses.isRevoked} (G41), so the refusal now admits the soft-deleted employee — the
 * one person a revocation exists for — and refuses the one who still has access.</p>
 *
 * <p>It mirrors {@link RevokedEmployeeRefusedException} in the opposite direction (record T84:
 * {@code apply} on a revoked employee): same helper ({@code EmployeeStatuses.isRevoked}), so the
 * surface and the worker cannot disagree about who is revoked, and the same thin semantic alias
 * of {@link ConflictException} — the handler resolves it by inheritance, the refusal reaches the wire as
 * a named <b>409</b> with its message intact (record T86), and no new handler is added.</p>
 */
public class RevokedEmployeeRequiredException extends ConflictException {

    public RevokedEmployeeRequiredException(UUID employeeId) {
        super("Revocation requires a revoked employee (Terminated or disabled): employee " + employeeId
                + " still has access, so there is no revocation to request");
    }
}
