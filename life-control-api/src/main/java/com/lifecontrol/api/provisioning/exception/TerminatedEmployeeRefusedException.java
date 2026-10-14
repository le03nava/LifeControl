package com.lifecontrol.api.provisioning.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * 409 category for an explicit access request refused because the employee's employment status is
 * {@code Terminated} (record T84).
 *
 * <p>There is nothing to activate for a person who has been given leave, and the refusal is not a
 * matter of taste: the {@code ACTIVATE} kind's first step <b>creates or links the account and sends
 * the invitation</b> ({@code AccessProvisioningWorkerHandler} → {@code AccessProvisioningAccountService.activate}),
 * so honouring the request would onboard the very person the revocation exists to disable. The
 * status rule is read with {@code EmployeeStatuses.isTerminated}, the same helper the projection
 * already uses, so the surface and the worker cannot disagree about who is revoked.</p>
 *
 * <p>It is a thin semantic alias of {@link ConflictException} — the shape
 * {@code SelfApprovalRefusedException} took for the same measured reason (record T73): the handler
 * resolves it by inheritance, the refusal reaches the wire as a named <b>409</b>, and the message
 * survives instead of being replaced by the hardcoded body of the {@code AccessDeniedException}
 * handler.</p>
 */
public class TerminatedEmployeeRefusedException extends ConflictException {

    public TerminatedEmployeeRefusedException(UUID employeeId) {
        super("A Terminated employee cannot be provisioned: no access is granted to employee " + employeeId);
    }
}
