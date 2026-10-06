package com.lifecontrol.api.provisioning.exception;

/**
 * The company of an employee has no usable {@code email_domain}, so the invitation email has no
 * deliverable address and the activation path is refused <b>before</b> anything is written to
 * Keycloak (record T33).
 *
 * <p>It is a plain unchecked domain exception and deliberately <b>not</b> an HTTP error: the
 * invitation is triggered by the access-provisioning worker (W4), which records the message as the
 * task's {@code last_error}. Refusing first is the fail-closed rule of records T10/T13: creating an
 * account for a person who has no way in would leave an orphan in another system and a task that
 * claims access was granted.</p>
 */
public class InvitationNotDeliverableException extends RuntimeException {

    public InvitationNotDeliverableException(String message) {
        super(message);
    }
}
