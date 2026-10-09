package com.lifecontrol.api.provisioning.dto;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The roles a convergence <b>would</b> touch for an employee: what is missing from the account
 * ({@code added}) and what the account holds inside the grantable universe that is no longer required
 * ({@code removed}).
 *
 * <p>This is the read-side twin of {@link RoleConvergenceResult#granted()} and
 * {@link RoleConvergenceResult#removed()}, computed with the exact rule the role convergence applies
 * (record T34/T35): {@code added = required − current} and
 * {@code removed = (current ∩ GrantablePositionRoles.names()) − required}. A held role outside the
 * grantable universe — {@code lc-admin} among them — is never reported as removable (record T12/T34),
 * so the screen never proposes a removal the convergence would refuse to perform.</p>
 *
 * <p>Both sets are normalized in the compact constructor to unmodifiable {@link TreeSet}s, so the
 * iteration order is ascending and deterministic and the values are safe to assert on and to log,
 * exactly like {@link RoleConvergenceResult}.</p>
 *
 * @param added the required roles the account does not hold
 * @param removed the account's grantable roles that are no longer required
 */
public record AccessProvisioningRoleDiff(Set<String> added, Set<String> removed) {

    public AccessProvisioningRoleDiff {
        added = normalize(added);
        removed = normalize(removed);
    }

    private static Set<String> normalize(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new TreeSet<>(roles));
    }
}
