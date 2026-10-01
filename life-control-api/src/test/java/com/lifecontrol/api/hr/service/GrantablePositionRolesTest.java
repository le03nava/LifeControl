package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.common.security.Roles;
import com.lifecontrol.api.common.security.ScopeLevel;
import java.util.Arrays;
import java.util.HashSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the frozen allowlist of D14 / T19.
 *
 * <p>Two load-bearing invariants: {@code lc-admin} must never be grantable (D7/G8), and the frozen
 * copy must not drift from the union of {@code ScopeLevel.roleNames()} — because computing that union
 * at runtime would let an unrelated edit to a scope level silently widen this security boundary.</p>
 */
@DisplayName("GrantablePositionRoles frozen allowlist")
class GrantablePositionRolesTest {

    @Test
    @DisplayName("lc-admin is never grantable from a position template")
    void adminIsNeverGrantable() {
        assertThat(GrantablePositionRoles.names())
                .as("lc-admin must never be grantable: isAdmin() short-circuits every verifyCompany*Access, "
                        + "so a template could escalate to full admin across companies (D7)")
                .doesNotContain(Roles.ADMIN);
    }

    @Test
    @DisplayName("the frozen copy does not diverge from ScopeLevel.roleNames() in either direction")
    void frozenCopyMatchesScopeLevelRegistry() {
        var fromScopeLevels = Arrays.stream(ScopeLevel.values())
                .flatMap(level -> level.roleNames().stream())
                .collect(Collectors.toSet());
        var frozen = GrantablePositionRoles.names();

        var gainedByScopeLevels = new HashSet<>(fromScopeLevels);
        gainedByScopeLevels.removeAll(frozen);
        var gainedByFrozen = new HashSet<>(frozen);
        gainedByFrozen.removeAll(fromScopeLevels);

        assertThat(gainedByScopeLevels)
                .as(
                        "ScopeLevel.roleNames() gained %s, which the frozen position-role allowlist does not carry. "
                                + "Widening this security boundary is a decision, not a side effect; if the widening is "
                                + "intended, update GrantablePositionRoles deliberately.",
                        gainedByScopeLevels)
                .isEmpty();
        assertThat(gainedByFrozen)
                .as(
                        "The frozen position-role allowlist gained %s, which ScopeLevel.roleNames() does not carry. "
                                + "Widening this security boundary is a decision, not a side effect; if the widening is "
                                + "intended, say so deliberately.",
                        gainedByFrozen)
                .isEmpty();
    }
}
