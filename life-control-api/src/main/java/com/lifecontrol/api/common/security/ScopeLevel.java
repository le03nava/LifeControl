package com.lifecontrol.api.common.security;

import java.util.List;

/**
 * Ordered registry of the company-scoped authorization hierarchy and its JWT claims.
 *
 * <p>Levels are declared from the broadest scope ({@link #COMPANY}) to the most specific
 * ({@link #STORE}). Each level carries the JWT claim that holds the authorized IDs, the roles
 * that grant access at that scope, and whether the entity ID is mandatory when verifying access.
 * This is the single place where the scope&rarr;claim&rarr;role rules are declared, so
 * {@code CurrentUserContext} can verify access generically instead of repeating one nested
 * {@code if} pyramid per entity type.</p>
 *
 * <p>Role names are not duplicated here: they are referenced from {@link Roles}, the single
 * source of truth for role literals. This type centralizes the <em>rules</em> that map those
 * roles to a scope.</p>
 *
 * <p>Parent levels are {@linkplain #isRequired() mandatory}; the entity's own level is optional
 * so callers can verify create operations before the entity exists (e.g. a {@code null} zone id
 * when creating a zone inside an already-resolved region).</p>
 */
public enum ScopeLevel {

    /** Company level, scoped by the {@code company_id} claim. */
    COMPANY("company_id", "company", true, Roles.COMPANY),

    /** Company-country level, scoped by the {@code company_country_id} claim. */
    COUNTRY("company_country_id", "company country", true, Roles.COMPANY_COUNTRY, Roles.COMPANY_COUNTRY_READ),

    /** Company-region level, scoped by the {@code company_region_id} claim. */
    REGION("company_region_id", "company region", false, Roles.COMPANY_REGION, Roles.COMPANY_REGION_READ),

    /** Company-zone level, scoped by the {@code company_zone_id} claim. */
    ZONE("company_zone_id", "company zone", false, Roles.COMPANY_ZONE, Roles.COMPANY_ZONE_READ),

    /**
     * Company-store level, scoped by the {@code company_store_id} claim.
     *
     * <p>{@link Roles#RECEIVING} belongs in this list because
     * {@code CurrentUserContext#verifyCompanyStoreAccess} routes on {@code hasAnyRole(STORE)}: a
     * role absent from {@code roleNames()} takes the broadest-granted fallback and can never pass
     * the store check on its own. Conversely, the parent levels of the store path are verified
     * against {@linkplain #claim() claims}, not against the parent roles ({@code verifyLevel} never
     * calls {@code hasAnyRole}), so a caller holding only {@code lc-receiving} must carry the
     * {@code company_id} &rarr; {@code company_store_id} claim path in the token.</p>
     */
    STORE("company_store_id", "company store", false, Roles.COMPANY_STORE, Roles.COMPANY_STORE_READ, Roles.RECEIVING);

    private static final List<ScopeLevel> ORDERED = List.of(values());

    private final String claim;
    private final String label;
    private final boolean required;
    private final List<String> roleNames;

    ScopeLevel(String claim, String label, boolean required, String... roleNames) {
        this.claim = claim;
        this.label = label;
        this.required = required;
        this.roleNames = List.of(roleNames);
    }

    /** JWT claim that carries the authorized IDs for this scope. */
    public String claim() {
        return claim;
    }

    /** Human-readable label used in denial messages, e.g. {@code "company country"}. */
    public String label() {
        return label;
    }

    /**
     * Whether the entity ID for this level must be present when verifying access at this scope.
     *
     * <p>Parent levels are mandatory; the entity's own level is optional so callers can verify
     * create operations before the entity exists.</p>
     */
    public boolean isRequired() {
        return required;
    }

    /** Role names (without the {@code ROLE_} prefix) that grant access at this scope. */
    public List<String> roleNames() {
        return roleNames;
    }

    /**
     * Levels from the root {@link #COMPANY} down to and including {@code target}, in hierarchy
     * order. Used to resolve the broadest role and to verify the matching claim path.
     */
    public static List<ScopeLevel> companyTo(ScopeLevel target) {
        return List.copyOf(ORDERED.subList(0, target.ordinal() + 1));
    }
}
