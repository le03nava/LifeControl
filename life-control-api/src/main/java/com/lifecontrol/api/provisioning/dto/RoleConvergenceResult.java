package com.lifecontrol.api.provisioning.dto;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The three role sets a role-convergence run derives, carried separately (unit W2b, record T35).
 *
 * <p>The snapshot the access-provisioning worker (W4) will persist to
 * {@code access_provisioning_applied_roles} is {@code granted ∪ removed} — the roles the run
 * <b>touched</b> — and never a "final state". That distinction is load-bearing for the most
 * destructive convergence there is: a {@code Terminated} employee has an <b>empty</b> required set, so
 * a final-state reading would write zero rows for a run that removed everything, emptying the history
 * exactly where it matters (record T35). The three sets are exposed separately so a reader can render
 * required-versus-current without re-deriving anything.</p>
 *
 * <p>The sets are normalized in the compact constructor to unmodifiable {@link TreeSet}s, so both
 * the values and their iteration order (ascending, deterministic) are fixed and safe to assert on and
 * to log.</p>
 *
 * @param required the roles the current contract and its position template require
 * @param granted the required roles the account did not already hold
 * @param removed the roles the account held that are inside the grantable universe and no longer
 *     required
 */
public record RoleConvergenceResult(Set<String> required, Set<String> granted, Set<String> removed) {

    public RoleConvergenceResult {
        required = normalize(required);
        granted = normalize(granted);
        removed = normalize(removed);
    }

    private static Set<String> normalize(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new TreeSet<>(roles));
    }
}
