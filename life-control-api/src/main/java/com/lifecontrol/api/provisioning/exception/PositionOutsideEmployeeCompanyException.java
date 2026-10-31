package com.lifecontrol.api.provisioning.exception;

/**
 * The role convergence refused to run because the position of the current contract belongs to a
 * <b>company other than the employee's</b>: the {@code position → department → company} chain did not
 * resolve to the employee's company (record T36).
 *
 * <p><b>The chain is asked as an id predicate, not walked.</b> Ownership is decided by
 * {@code departments.company_id = :employeeCompanyId and positions.id = :positionId}
 * ({@code PositionRepository#findByDepartmentCompanyIdAndId}), because {@code departments} is one of the
 * sixteen tables ADR-0005 arms: the department of another company is a row this caller may not see, so
 * traversing into it made Hibernate raise {@code EntityNotFoundException} — "No row with the given
 * identifier exists" — instead of this refusal. A {@code null} position, a {@code null} employee
 * company, or a row the policy filters out are all the same refusal, so the failure mode is a named
 * domain exception and never a {@link NullPointerException} or a mapping exception. It is a plain
 * unchecked domain exception and deliberately <b>not</b> an HTTP error: its caller is the
 * access-provisioning worker (W4).</p>
 */
public class PositionOutsideEmployeeCompanyException extends RuntimeException {

    public PositionOutsideEmployeeCompanyException(String message) {
        super(message);
    }
}
