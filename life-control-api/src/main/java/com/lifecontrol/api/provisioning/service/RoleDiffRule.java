package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.hr.service.GrantablePositionRoles;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one role-diff rule the provisioning write path applies and this unit's Access read path
 * composes, so the diff the Access section is meant to render is by construction the diff the
 * convergence would apply.
 *
 * <p>Both sides reach the same arithmetic here: the write path
 * {@link AccessProvisioningRoleService#convergeRoles(com.lifecontrol.api.hr.model.Employee)} and this
 * unit's Access read path, which composes this rule instead of re-deriving it. The removal scope is
 * {@link GrantablePositionRoles#names()}: a held role outside the grantable universe — {@code lc-admin}
 * among them — is never reported and never removed (records T12/T34), so the screen never proposes a
 * removal the convergence would refuse to perform.</p>
 */
final class RoleDiffRule {

    private RoleDiffRule() {}

    /**
     * {@code additions = required − current}: the required roles the account does not already hold.
     */
    static Set<String> additions(Set<String> required, Set<String> current) {
        var additions = new TreeSet<>(required);
        additions.removeAll(current);
        return additions;
    }

    /**
     * {@code removals = (current ∩ GrantablePositionRoles.names()) − required}: the held roles inside
     * the grantable universe that are no longer required.
     */
    static Set<String> removals(Set<String> current, Set<String> required) {
        var removals = new TreeSet<>(current);
        removals.retainAll(GrantablePositionRoles.names());
        removals.removeAll(required);
        return removals;
    }
}
