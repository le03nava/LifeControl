package com.lifecontrol.api.provisioning.model;

/**
 * What {@code AccessProvisioningAccountService} did to the Keycloak account of an employee.
 *
 * <p>It is the caller's (W4's) record of <b>which</b> path the account lifecycle took, not a stored
 * state: nothing persists this value, because the fact it describes is
 * {@code employees.keycloak_user_id} plus the account that already existed. The three values are:</p>
 *
 * <ul>
 *   <li>{@link #CREATED} — no account existed for the address, one was created;</li>
 *   <li>{@link #LINKED} — the address already had <b>exactly one</b> account and it was linked
 *       (record T5/T27);</li>
 *   <li>{@link #ALREADY_LINKED} — the employee already carried a
 *       {@code keycloak_user_id}; no identity-provider call and no write happened.</li>
 * </ul>
 */
public enum AccountProvisioningOutcome {
    /** A new account was created and its id persisted. */
    CREATED,
    /** An existing account was linked, under the exactly-one-exact-match rule. */
    LINKED,
    /** The employee already had a linked account; the identity provider was not touched. */
    ALREADY_LINKED
}
