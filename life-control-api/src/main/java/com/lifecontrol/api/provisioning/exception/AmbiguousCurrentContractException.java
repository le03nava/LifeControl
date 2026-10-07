package com.lifecontrol.api.provisioning.exception;

/**
 * The role convergence refused to run because the employee's <b>current contract is ambiguous</b>:
 * {@code findEnabledContractCoveringDate} returned more than one row (record T36).
 *
 * <p>The database's partial exclusion constraint {@code ex_employee_contracts_no_overlap} makes more
 * than one covering contract a <b>violated invariant</b> rather than a set to pick from, so the
 * convergence refuses instead of choosing one — the same "never a pick" rule as the account link.
 * It is a plain unchecked domain exception and deliberately <b>not</b> an HTTP error: this capability
 * has no endpoint, and its caller is the access-provisioning worker (W4).</p>
 */
public class AmbiguousCurrentContractException extends RuntimeException {

    public AmbiguousCurrentContractException(String message) {
        super(message);
    }
}
