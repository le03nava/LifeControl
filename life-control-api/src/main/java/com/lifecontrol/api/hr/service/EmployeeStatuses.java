package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.status.model.Status;

/**
 * The seeded status names of the {@code EMPLOYEE_STATUS} family, in one place.
 *
 * <p>{@link #TERMINATED} mirrors the {@code Terminated} row that
 * {@code V20__employee_registry.sql} seeds under the {@code EMPLOYEE_STATUS} family (decisions T3
 * and T4: the repository has no native enum, and the seeded names are English). {@link
 * #isTerminated(Employee)} carries the single copy of the rule that reads it — compared
 * case-insensitively, as the seeded value is — and it exists precisely because a second copy of a
 * seeded name is a second truth: two services each holding their own literal would be
 * desynchronized by the first rename of the seed, which is the defect the record refuses (see D4's
 * reasoning).</p>
 *
 * <p>This is a static helper rather than a Spring bean, following {@link GrantablePositionRoles} in
 * this package: it is a constant and a pure predicate over an entity, so it needs no injection.</p>
 */
public final class EmployeeStatuses {

    /** The status name seeded by {@code V20__employee_registry.sql} for a terminated employee. */
    public static final String TERMINATED = "Terminated";

    private EmployeeStatuses() {}

    /** Whether {@code employee} holds the {@code Terminated} status of the {@code EMPLOYEE_STATUS} family. */
    public static boolean isTerminated(Employee employee) {
        return employee != null && isTerminated(employee.getStatus());
    }

    /**
     * Whether {@code employee} holds <b>no access</b>: the {@code Terminated} status, or the soft
     * delete's {@code enabled = false}.
     *
     * <p>This is the single copy of the rule gap <b>G41</b> was about
     * ({@code employee-disable-revokes-access}): the projection used to read the status alone, so a
     * soft-deleted employee whose contract still covered today kept every role and claim. The two facts
     * are one verdict — the person is out — and they are compared here so that the derivation, the
     * gate's diff, the worker's convergence and the account half can never disagree about it.</p>
     *
     * <p>A null {@code enabled} is <b>not</b> a revocation on purpose: the column is
     * {@code NOT NULL DEFAULT true} and the entity initializes it to {@code true}, so an unset flag only
     * ever exists on a builder that has not run the field initializer, never on a loaded row.</p>
     */
    public static boolean isRevoked(Employee employee) {
        return isTerminated(employee) || (employee != null && Boolean.FALSE.equals(employee.getEnabled()));
    }

    /**
     * Whether {@code status} is the {@code Terminated} row of the {@code EMPLOYEE_STATUS} family.
     *
     * <p>The same rule expressed over the {@link Status} itself, for the call sites that hold a
     * resolved status and no {@link Employee} — {@code EmployeeService} validates the status of a
     * write before the employee exists.</p>
     */
    public static boolean isTerminated(Status status) {
        return status != null && TERMINATED.equalsIgnoreCase(status.getStatusName());
    }
}
