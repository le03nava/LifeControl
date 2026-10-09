package com.lifecontrol.api.common.security;

/**
 * Central constants for authorization roles.
 *
 * <p>Role names are single source of truth: every {@code @PreAuthorize} in the codebase must
 * reference these constants instead of hardcoding role literals.</p>
 *
 * <p>Most {@code lc-*} roles are provisioned as <b>client roles</b> of {@code life-control-client}
 * by {@code docker/scripts/keycloak-setup.sh}, while the legacy {@code life-control-*}
 * roles are <b>realm roles</b>. Both converge to {@code ROLE_<name>} authorities via
 * {@code JwtDecoderConfig}, so they are interchangeable in {@code hasRole}/{@code hasAnyRole}.</p>
 *
 * <p>{@code lc-admin} is the admin role for feature controllers. The realm role {@code admin}
 * is <b>not</b> used in {@code @PreAuthorize}: it protects the {@code /api/users-admin/**}
 * endpoints at URL level in {@code SecurityConfig} ({@code ROLE_admin}).</p>
 */
public final class Roles {

    private Roles() {}

    // ---- Admin ----

    /** Admin role for feature controllers. Client role of {@code life-control-client}. */
    public static final String ADMIN = "lc-admin";

    /** Legacy admin realm role, kept only for backward compatibility in {@code CurrentUserContext}.
     *  Retired once Keycloak is fully migrated to {@code lc-*} client roles. */
    public static final String LIFE_CONTROL_ADMIN = "life-control-admin";

    /** Legacy country realm role (product catalog legacy), kept only for backward compatibility
     *  in {@code CurrentUserContext}. Retired once Keycloak is fully migrated to {@code lc-*} client roles. */
    public static final String LIFE_CONTROL_COUNTRY = "life-control-country";

    /** Keycloak admin role, enforced at URL level for {@code /api/users-admin/**}. */
    public static final String USER_ADMIN = "admin";

    // ---- Catalogs (write roles, one per catalog) ----

    public static final String COUNTRY = "lc-country";
    public static final String STATUS = "lc-status";
    public static final String STATUS_TYPE = "lc-status-type";
    public static final String PAYMENT_METHOD = "lc-payment-method";
    public static final String MEASURE_UNIT = "lc-measure-unit";
    public static final String PRODUCT_SUPPLIER = "lc-product-supplier";

    /** HR department catalog write role. One write role per catalog, as {@code lc-department}.
     *  Client role of {@code life-control-client}; company-scoped through {@link ScopeLevel#COMPANY}. */
    public static final String DEPARTMENT = "lc-department";

    /** HR position catalog write role. One write role per catalog, as {@code lc-position}.
     *  Client role of {@code life-control-client}; company-scoped through {@link ScopeLevel#COMPANY}. */
    public static final String POSITION = "lc-position";

    /** HR seniority-level catalog write role. One write role per catalog, as
     *  {@code lc-seniority-level}. Client role of {@code life-control-client}; seniority levels are
     *  global reference data, so this role is deliberately unscoped and absent from every
     *  {@link ScopeLevel}. */
    public static final String SENIORITY_LEVEL = "lc-seniority-level";

    // ---- Feature roles ----

    public static final String SALES = "lc-sales";
    public static final String COMPANY = "lc-company";
    public static final String COMPANY_READ = "lc-company-read";
    public static final String COMPANY_COUNTRY = "lc-company-country";
    public static final String COMPANY_COUNTRY_READ = "lc-company-country-read";
    public static final String COMPANY_REGION = "lc-company-region";
    public static final String COMPANY_REGION_READ = "lc-company-region-read";
    public static final String COMPANY_ZONE = "lc-company-zone";
    public static final String COMPANY_ZONE_READ = "lc-company-zone-read";
    public static final String COMPANY_STORE = "lc-company-store";
    public static final String COMPANY_STORE_READ = "lc-company-store-read";
    public static final String RECEIVING = "lc-receiving";

    /** Scheduling write role: manages the store's bookable activities and appointments.
     *  Client role of {@code life-control-client}. */
    public static final String SCHEDULING = "lc-scheduling";

    /** Scheduling read-only role. Client role of {@code life-control-client}. */
    public static final String SCHEDULING_READ = "lc-scheduling-read";

    /** HR employee-record write role. A <b>functional</b> role, like {@link #SENIORITY_LEVEL}: it
     *  grants writing the employee record and is deliberately absent from every {@link ScopeLevel}
     *  and from {@code GrantablePositionRoles}. Employee-record write is not a tenancy level, so it
     *  must not be delegable by a position template; the company scope is still verified through the
     *  {@code company_id} claim. Client role of {@code life-control-client}. */
    public static final String EMPLOYEE = "lc-employee";

    /** Identity-provisioning read role: gates {@code GET
     *  /api/companies/{companyId}/employees/{employeeId}/access}, the Access section's own read
     *  (linked account, required-versus-current roles and their diff, open task with its
     *  {@code attempts} against the ceiling, and the derived claim values). It is deliberately
     *  <b>distinct</b> from {@link #EMPLOYEE}: provisioning an identity and editing the HR record are
     *  different powers, and identity provisioning gets its own role for that reason
     *  ({@code hr-org-structure} D7). There is <b>no read-only pair</b> for now — the screen shows role
     *  names, not PII, so {@code lc-admin} and this role are the only read gate
     *  ({@code employee-access-provisioning} O5). Client role of {@code life-control-client}. */
    public static final String EMPLOYEE_ACCESS = "lc-employee-access";
}
