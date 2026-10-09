package com.lifecontrol.api.provisioning.exception;

import com.lifecontrol.api.exception.ConflictException;

/**
 * 409 category for a status write the access-provisioning state machine refuses: a pair that is not
 * one of the legal edges of {@code AccessProvisioningTaskService}'s transition map, a
 * self-transition, or an exit from a terminal status.
 *
 * <p>It is a thin semantic alias of {@link ConflictException}, exactly like
 * {@code purchaseorder.exception.InvalidStatusTransitionException}, so
 * {@code GlobalExceptionHandler} resolves it by inheritance and no handler is added.</p>
 */
public class InvalidTaskStatusTransitionException extends ConflictException {

    public InvalidTaskStatusTransitionException(String from, String to) {
        super("Invalid status transition: " + from + " → " + to);
    }
}
