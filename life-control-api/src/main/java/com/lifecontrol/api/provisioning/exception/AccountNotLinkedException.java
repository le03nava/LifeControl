package com.lifecontrol.api.provisioning.exception;

import com.lifecontrol.api.exception.ConflictException;

/**
 * The operation refused to run because the employee has <b>no linked Keycloak account</b>:
 * {@code employee.keycloakUserId} is {@code null} (record T36).
 *
 * <p>Linking the account is the account lifecycle's job (unit W2a), and a silent no-op would hide
 * exactly the wiring defect this record is about — roles applied to nobody. The worker records the
 * message as the task's {@code last_error}, and it is also the refusal the operator sees when there
 * is nothing to re-send an invitation to (record T69).</p>
 *
 * <p><b>It is a 409, and that is a measured decision rather than a taxonomy preference</b> (record
 * T86): as a plain {@link RuntimeException} it fell through {@code GlobalExceptionHandler}'s generic
 * arm, so the moment the resend route exposed it over HTTP the refusal would have reached the
 * operator as <b>500 "An unexpected error occurred"</b> — a refusal by name without its name.
 * Extending {@link ConflictException} is the shape record T73 already measured for
 * {@code SelfApprovalRefusedException}: the handler resolves it by inheritance, the message survives,
 * and no new handler is added. The change is inert on the worker path, whose catch is
 * {@link RuntimeException}.</p>
 */
public class AccountNotLinkedException extends ConflictException {

    public AccountNotLinkedException(String message) {
        super(message);
    }
}
