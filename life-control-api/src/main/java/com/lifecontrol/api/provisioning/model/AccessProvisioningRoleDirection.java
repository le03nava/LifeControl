package com.lifecontrol.api.provisioning.model;

/**
 * Direction of one role inside a task's frozen reviewed diff, persisted as a string in the
 * {@code access_provisioning_reviewed_roles.direction VARCHAR(10)} column: the Java counterpart of
 * the stored value, following the enum-in-a-column convention of the module
 * ({@link AccessProvisioningTaskStatus}, {@link AccessProvisioningTaskKind}).
 *
 * <p>The column carries no CHECK and no native enum, exactly like {@code kind} and {@code status}:
 * the value set is this enum's and the database is not a second guard, which is V22's own stance
 * ("zero {@code CHECK (col IN ...)}") kept intact by decision T71.</p>
 *
 * <p>The direction is what turns the two role sets into the thing a human reviews (decision T71): it
 * is the added / removed vocabulary the landed read surface already renders
 * ({@code AccessProvisioningRoleDiff}), never an instruction to the worker.</p>
 *
 * <p>These are <b>values only</b>. There is deliberately no classification here — no
 * {@code isGrant()}, no set membership — because the enum is the entity's column type.</p>
 */
public enum AccessProvisioningRoleDirection {
    /** The role is required and not present on the account: applying the diff adds it. */
    GRANT,
    /** The role is present on the account and not required: applying the diff removes it. */
    REVOKE
}
