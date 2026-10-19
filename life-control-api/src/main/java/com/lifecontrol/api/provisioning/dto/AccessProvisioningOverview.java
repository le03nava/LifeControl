package com.lifecontrol.api.provisioning.dto;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The whole read model of one employee's access, composed by
 * {@link com.lifecontrol.api.provisioning.service.AccessProvisioningQueryService} for the Access
 * section.
 *
 * <p>It answers the four questions the section shows, without re-deriving anything on the client:</p>
 *
 * <ul>
 *   <li><b>Is the account linked?</b> {@code accountLinked} is {@code true} exactly when
 *       {@code employees.keycloak_user_id} is present and non-blank, and {@code keycloakUserId}
 *       carries it for display. When it is absent the overview says so explicitly and
 *       {@code currentRoles} is empty: there is no account to read roles from, so the identity
 *       provider is never asked with a {@code null} user id.</li>
 *   <li><b>What is required against what is held?</b> {@code requiredRoles} comes from the same
 *       derivation the write path converges, and {@code currentRoles} is read live from the identity
 *       provider — never mirrored locally (record T7). {@code roleDiff} is the two sets'
 *       {@code added}/{@code removed} projection.</li>
 *   <li><b>What did the reviewer actually freeze?</b> {@code frozenRoleDiff} is the set the open task
 *       froze for review (record T92), and it is a <b>different fact</b> from {@code roleDiff}: the
 *       live one is recomputed on every read, while the frozen one is what a human is being asked to
 *       approve or refuse — so a decision is rendered against the frozen set and not against a diff
 *       that moved after the request. It is {@code null} when no task is open, and also when the open
 *       task carries no frozen rows (a task that never needed a human: the auto-applied ones). The
 *       UI has to tell the two apart rather than render an empty set.</li>
 *   <li><b>What task is open?</b> {@code openTask} is the newest task in an open status, or
 *       {@code null} when none is open; {@code history} is the employee's tasks newest first.</li>
 *   <li><b>What would the token carry?</b> {@code claims} is the five tenancy claim values the
 *       membership projection derives (records T1/T39), or five empty lists when the account is not
 *       linked.</li>
 *   <li><b>Is the worker that applies the tasks even running?</b> {@code workerEnabled} is the
 *       effective {@code app.provisioning.worker.enabled} value (record S3-D), so the section can say
 *       that an open task will not advance on its own when the switch is off. It is the same property
 *       the scheduler's own condition reads, not a second source of truth.</li>
 * </ul>
 *
 * <p>The role sets are normalized in the compact constructor to unmodifiable {@link TreeSet}s and the
 * history to an unmodifiable copy, so iteration order is deterministic and the value is safe to hand
 * to a renderer and to assert on.</p>
 *
 * @param keycloakUserId {@code employees.keycloak_user_id}, or {@code null} when the account is not
 *     linked
 * @param accountLinked whether the employee has a linked Keycloak account
 * @param requiredRoles the roles the current contract's position template requires
 * @param currentRoles the account's live client roles; empty when the account is not linked
 * @param roleDiff the diff the convergence would apply
 * @param frozenRoleDiff the set the open task froze for review ({@code GRANT} rows as
 *     {@code added}, {@code REVOKE} rows as {@code removed}), or {@code null} when no task is open or
 *     the open task froze nothing (record T92)
 * @param openTask the newest task in an open status, or {@code null} when none is open
 * @param history every task of the employee, newest first
 * @param claims the five tenancy claim values the account would publish
 * @param workerEnabled whether the access-provisioning worker pass runs in this environment (record
 *     S3-D)
 */
public record AccessProvisioningOverview(
        String keycloakUserId,
        boolean accountLinked,
        Set<String> requiredRoles,
        Set<String> currentRoles,
        AccessProvisioningRoleDiff roleDiff,
        AccessProvisioningRoleDiff frozenRoleDiff,
        AccessProvisioningTaskView openTask,
        List<AccessProvisioningTaskView> history,
        AccessProvisioningClaims claims,
        boolean workerEnabled) {

    public AccessProvisioningOverview {
        requiredRoles = normalize(requiredRoles);
        currentRoles = normalize(currentRoles);
        history = history == null ? List.of() : List.copyOf(history);
    }

    private static Set<String> normalize(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new TreeSet<>(roles));
    }
}
