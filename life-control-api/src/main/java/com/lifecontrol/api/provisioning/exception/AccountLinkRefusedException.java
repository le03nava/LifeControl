package com.lifecontrol.api.provisioning.exception;

/**
 * The account lifecycle refused to link an existing Keycloak account because the link rule was not
 * satisfied: the address is already taken, but not by <b>exactly one</b> account whose email equals
 * the employee's frozen corporate address (zero matches, or more than one — records T5/T27).
 *
 * <p>It is a plain unchecked domain exception and deliberately <b>not</b> an HTTP error: this
 * capability has no endpoint, and its caller is the access-provisioning worker (W4), which records
 * the message as the task's {@code last_error} and leaves the task {@code FAILED} and visible
 * (records T10/T13). Picking one of several candidate accounts would mean granting access to whoever
 * came first, which is exactly the defect this refusal exists to prevent.</p>
 */
public class AccountLinkRefusedException extends RuntimeException {

    public AccountLinkRefusedException(String message) {
        super(message);
    }
}
