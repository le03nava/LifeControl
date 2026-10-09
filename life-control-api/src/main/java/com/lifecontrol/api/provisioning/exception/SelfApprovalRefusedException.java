package com.lifecontrol.api.provisioning.exception;

import com.lifecontrol.api.exception.ConflictException;

/**
 * 409 category for rule <b>O3</b> of the employee-access gate: the actor who requested an
 * approval-gated task cannot be the one who approves it.
 *
 * <p>The rule is deliberately <b>approve-only</b>. A self-<i>rejection</i> grants nothing — it
 * withdraws the requester's own request — so refusing it would force an {@code lc-admin} to cancel
 * on the requester's behalf. O3 therefore lives in
 * {@link AccessProvisioningTaskService#approve(java.util.UUID, java.util.UUID, String)} alone, and
 * {@link AccessProvisioningTaskService#reject(java.util.UUID, java.util.UUID, String, String)}
 * accepts a self-decision.</p>
 *
 * <p>It is a thin semantic alias of {@link ConflictException}, exactly like
 * {@link InvalidTaskStatusTransitionException}, so {@code GlobalExceptionHandler} resolves it by
 * inheritance and the 409 carries this message instead of losing it to the hardcoded body of the
 * {@code AccessDeniedException} handler.</p>
 */
public class SelfApprovalRefusedException extends ConflictException {

    public SelfApprovalRefusedException(String actor) {
        super("The requester of an access request cannot approve it: " + actor);
    }
}
