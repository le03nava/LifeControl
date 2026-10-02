package com.lifecontrol.api.hr.service;

import java.util.Set;

/**
 * The frozen allowlist of roles a position template may grant (decisions D7 and D14).
 *
 * <p>This is a <b>frozen copy</b> of the union of every {@code ScopeLevel.roleNames()} entry at the
 * time the record was written, not a value computed from {@link
 * com.lifecontrol.api.common.security.ScopeLevel}. Computing it at runtime would let an unrelated
 * edit to a scope level silently widen this security boundary; a literal set makes any widening an
 * explicit, reviewable diff. {@link GrantablePositionRolesTest} pins both directions: the frozen copy
 * must not drift from the scope registry, and {@code lc-admin} must never appear.</p>
 *
 * <p>{@code lc-admin} is deliberately absent (D7): it short-circuits every {@code verifyCompany*Access}
 * through {@code isAdmin()}, so granting it from a position template would be an escalation to full
 * admin across every company. Several roles in the allowlist are also granted by no scope level of
 * their own outside this union; the union is the normative source, and every entry here is one of
 * those fifteen literals and nothing else.</p>
 */
public final class GrantablePositionRoles {

    private static final Set<String> FROZEN = Set.of(
            "lc-company",
            "lc-department",
            "lc-position",
            "lc-company-country",
            "lc-company-country-read",
            "lc-company-region",
            "lc-company-region-read",
            "lc-company-zone",
            "lc-company-zone-read",
            "lc-company-store",
            "lc-company-store-read",
            "lc-receiving",
            "lc-sales",
            "lc-scheduling",
            "lc-scheduling-read");

    private GrantablePositionRoles() {}

    /** The frozen set of grantable role names; callers must not assume an iteration order. */
    public static Set<String> names() {
        return FROZEN;
    }

    /** Whether {@code roleName} is in the frozen allowlist. */
    public static boolean includes(String roleName) {
        return FROZEN.contains(roleName);
    }
}
