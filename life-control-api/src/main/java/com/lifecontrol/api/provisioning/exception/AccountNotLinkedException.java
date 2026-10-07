package com.lifecontrol.api.provisioning.exception;

/**
 * The role convergence refused to run because the employee has <b>no linked Keycloak account</b>:
 * {@code employee.keycloakUserId} is {@code null} (record T36).
 *
 * <p>Linking the account is the account lifecycle's job (unit W2a), and a silent no-op here would
 * hide exactly the wiring defect this record is about — roles applied to nobody. It is a plain
 * unchecked domain exception and deliberately <b>not</b> an HTTP error: this capability has no
 * endpoint, and its caller is the access-provisioning worker (W4), which records the message as the
 * task's {@code last_error}.</p>
 */
public class AccountNotLinkedException extends RuntimeException {

    public AccountNotLinkedException(String message) {
        super(message);
    }
}
