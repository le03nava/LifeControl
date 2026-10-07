package com.lifecontrol.api.provisioning.exception;

/**
 * The role convergence refused to run because the position of the current contract belongs to a
 * <b>company other than the employee's</b>: the {@code position → department → company} chain did not
 * resolve to the employee's company (record T36).
 *
 * <p>The chain is resolved as a check and never assumed; a {@code null} anywhere in
 * {@code position.getDepartment().getCompany().getId()} or in {@code employee.getCompany().getId()}
 * is the same refusal, so the failure mode is a named domain exception and never a
 * {@link NullPointerException}. It is a plain unchecked domain exception and deliberately <b>not</b>
 * an HTTP error: this capability has no endpoint, and its caller is the access-provisioning worker
 * (W4).</p>
 */
public class PositionOutsideEmployeeCompanyException extends RuntimeException {

    public PositionOutsideEmployeeCompanyException(String message) {
        super(message);
    }
}
