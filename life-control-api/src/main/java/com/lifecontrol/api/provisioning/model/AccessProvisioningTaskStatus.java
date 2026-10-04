package com.lifecontrol.api.provisioning.model;

/**
 * Life-cycle status of an access-provisioning task, persisted as a string in the
 * {@code access_provisioning_tasks.status VARCHAR(20)} column, following the enum-in-a-column
 * convention of the module ({@code hr.model.ContractType}, {@code MovementType}): the column carries
 * no CHECK, and the enum is the Java counterpart of the stored value.
 *
 * <p>The states are the contract between the worker (W4), the approval gate (W5) and the UI
 * (record T2). The database treats {@code PENDING}, {@code APPROVAL_PENDING}, {@code RUNNING} and
 * {@code FAILED} as <b>open</b>: the partial unique index
 * {@code uq_access_provisioning_tasks_open} admits at most one of them per employee. {@code APPLIED}
 * and {@code REJECTED} are terminal and release that slot.</p>
 *
 * <p>These are <b>values only</b>. There is deliberately no transition logic here — no
 * {@code canTransitionTo}, no map — because the state machine and its guard belong to one service,
 * {@code com.lifecontrol.api.provisioning.service.AccessProvisioningTaskService}, and the enum is the
 * entity's column type, not the machine. A state that knew its own transitions would let any holder
 * of the entity change the state (record T17).</p>
 */
public enum AccessProvisioningTaskStatus {
    /** Written and waiting for a worker to pick it up. */
    PENDING,
    /** Waiting for a second pair of eyes because the diff carries a role outside the auto-apply set. */
    APPROVAL_PENDING,
    /** Claimed by a worker; the transition into this state is the claim (W4). */
    RUNNING,
    /** Converged and recorded: the terminal success. */
    APPLIED,
    /** Visible failure with its reason in {@code last_error}; retried with backoff (record T10). */
    FAILED,
    /** Refused by the gate; the terminal refusal. */
    REJECTED
}
