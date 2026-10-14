package com.lifecontrol.api.provisioning.exception;

import com.lifecontrol.api.exception.ConflictException;

/**
 * The company of an employee has no usable {@code email_domain}, so the invitation email has no
 * deliverable address and the activation path is refused <b>before</b> anything is written to
 * Keycloak (record T33).
 *
 * <p><b>It is a 409, and that is a measured decision rather than a taxonomy preference</b> (record
 * T86): as a plain {@link RuntimeException} it fell through {@code GlobalExceptionHandler}'s generic
 * arm, so the moment the explicit request surface exposed it over HTTP
 * ({@link com.lifecontrol.api.provisioning.service.AccessProvisioningRequestService#apply(java.util.UUID, java.util.UUID)})
 * the refusal would have reached the operator as <b>500 "An unexpected error occurred"</b> — a refusal
 * by name without its name. Extending {@link ConflictException} is the shape record T73 already
 * measured for {@code SelfApprovalRefusedException}: the handler resolves it by inheritance, the
 * message survives, and no new handler is added. The change is inert on the worker path, whose catch
 * is {@link RuntimeException} and records the message as the task's {@code last_error}.</p>
 *
 * <p>Refusing first is the fail-closed rule of records T10/T13: creating an account for a person who
 * has no way in would leave an orphan in another system and a task that claims access was granted.</p>
 */
public class InvitationNotDeliverableException extends ConflictException {

    public InvitationNotDeliverableException(String message) {
        super(message);
    }
}
