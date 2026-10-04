package com.lifecontrol.api.provisioning.model;

/**
 * Kind of work an access-provisioning task represents, persisted as a string in the
 * {@code access_provisioning_tasks.kind VARCHAR(20)} column, following the enum-in-a-column
 * convention of the module ({@code hr.model.ContractType}, {@code MovementType}): the column carries
 * no CHECK, and the enum is the Java counterpart of the stored value.
 *
 * <p>The kind is the task's <b>immutable reference</b> (record T3): the row names the employee and
 * the kind, never the role names, so whoever writes the row decides nothing. Re-typing an
 * {@code ACTIVATE} task into a {@code DEACTIVATE} would rewrite the intent after the fact, which is
 * why the entity has no setter for it.</p>
 *
 * <p>These are <b>values only</b>. There is deliberately no transition logic here — no
 * {@code canTransitionTo}, no map — because the state machine and its guard belong to W1b, and the
 * enum is the entity's column type, not the machine.</p>
 */
public enum AccessProvisioningTaskKind {
    /** Create or link the account and converge its roles and claims. */
    ACTIVATE,
    /** Disable the account, remove the template's roles and remove the claim attributes. */
    DEACTIVATE,
    /** Re-derive the desired state and converge against it, without an instruction from the caller. */
    RECONCILE
}
