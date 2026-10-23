package com.lifecontrol.api.provisioning.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * 409 category for an explicit access request refused because the employee is <b>revoked</b> —
 * {@code Terminated}, or soft-deleted ({@code enabled = false}) — records T84 and G41.
 *
 * <p>There is nothing to activate for a person who is out, and the refusal is not a matter of taste:
 * the {@code ACTIVATE} kind's first step <b>creates or links the account and sends the invitation</b>
 * ({@code AccessProvisioningWorkerHandler} → {@code AccessProvisioningAccountService.activate}), so
 * honouring the request would onboard the very person the revocation exists to disable. A fresh
 * activation link from {@code resend} is a way back in for the same reason.</p>
 *
 * <p><b>The name states the rule the guard enforces.</b> The class was named after
 * {@code TerminatedEmployeeRefusedException} while {@code apply} and {@code resend} read
 * {@code EmployeeStatuses.isTerminated}; the projection's single rule is
 * {@code EmployeeStatuses.isRevoked} (G41), and a name that answers a narrower question
 * than the code does is the defect this class is supposed to prevent. Both facts are one verdict — the
 * person is out — and every surface that refuses them reads that one helper, so the surface and the
 * worker cannot disagree about who is revoked.</p>
 *
 * <p>It is a thin semantic alias of {@link ConflictException} — the shape
 * {@code SelfApprovalRefusedException} took for the same measured reason (record T73): the handler
 * resolves it by inheritance, the refusal reaches the wire as a named <b>409</b>, and the message
 * survives instead of being replaced by the hardcoded body of the {@code AccessDeniedException}
 * handler.</p>
 */
public class RevokedEmployeeRefusedException extends ConflictException {

    public RevokedEmployeeRefusedException(UUID employeeId) {
        super("A revoked employee (Terminated or disabled) cannot be provisioned: no access is granted"
                + " to employee " + employeeId);
    }
}
