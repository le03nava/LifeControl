package com.lifecontrol.api.provisioning.exception;

/**
 * The membership projection refused to run because the store-assignment derivation produced a company
 * scope that is <b>not exactly one company</b>: {@code companyIds} carried zero or more than one id
 * (records T1 and T39).
 *
 * <p>The five {@code company_*} attributes are an <b>authorization input</b>, and the invariant the
 * guard reads at the root of the token is one {@code company_id} per person: a second one would widen
 * the caller's reach into another tenant, and none would leave the required claim absent. So more than
 * one is a <b>violated invariant</b> rather than a set to publish, and the projection fails closed
 * <b>before</b> the first write instead of writing a scope it cannot justify — the same "never a pick"
 * rule as the account link (T27) and the ambiguous current contract (T36).</p>
 *
 * <p>It is a plain unchecked domain exception and deliberately <b>not</b> an HTTP error: this
 * capability has no endpoint, and its caller is the access-provisioning worker (W4), which records the
 * message as the task's {@code last_error}.</p>
 */
public class CompanyScopeInvariantException extends RuntimeException {

    public CompanyScopeInvariantException(String message) {
        super(message);
    }
}
