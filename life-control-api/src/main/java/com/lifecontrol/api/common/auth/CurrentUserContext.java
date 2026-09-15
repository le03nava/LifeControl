package com.lifecontrol.api.common.auth;

import com.lifecontrol.api.common.security.Roles;
import com.lifecontrol.api.common.security.ScopeLevel;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Request-scoped component that extracts and caches the current user's
 * company IDs and roles from the JWT token in the SecurityContext.
 *
 * <p>The bean uses a scoped proxy so it can be safely injected into
 * singleton-scoped services. Each HTTP request gets its own instance
 * backed by the SecurityContextHolder contents.
 *
 * <p>Company IDs are parsed from JWT claims (one per {@link ScopeLevel}), which may
 * contain a single UUID or multiple comma-separated UUIDs. Malformed values are silently skipped.
 *
 * <p>Scope verification is generic: {@link ScopeLevel} is the registry of claims, roles and
 * hierarchy, so access checks resolve the broadest granted role and verify the claim path up to
 * that scope instead of repeating one nested {@code if} pyramid per entity type.
 */
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class CurrentUserContext {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserContext.class);

    private final Map<ScopeLevel, Set<UUID>> claimIds = new EnumMap<>(ScopeLevel.class);
    private Set<String> authorities;
    private Boolean admin;
    private String userId;
    private String username;

    /**
     * Returns the set of company IDs extracted from the JWT {@code company_id} claim.
     * The claim may be a single UUID, a comma-separated string, or a JSON array.
     * Whitespace is trimmed, malformed UUIDs are silently skipped, and duplicates are removed.
     *
     * @return an immutable set of parsed UUIDs; empty if none found
     */
    public Set<UUID> getCompanyIds() {
        return idsFor(ScopeLevel.COMPANY);
    }

    /**
     * Returns the set of company country IDs extracted from the JWT {@code company_country_id} claim.
     * The claim may be a single UUID, a comma-separated string, or a JSON array.
     * Whitespace is trimmed, malformed UUIDs are silently skipped, and duplicates are removed.
     *
     * @return an immutable set of parsed UUIDs; empty if none found
     */
    public Set<UUID> getCompanyCountryIds() {
        return idsFor(ScopeLevel.COUNTRY);
    }

    /**
     * Returns the set of company region IDs extracted from the JWT {@code company_region_id} claim.
     * The claim may be a single UUID, a comma-separated string, or a JSON array.
     * Whitespace is trimmed, malformed UUIDs are silently skipped, and duplicates are removed.
     *
     * @return an immutable set of parsed UUIDs; empty if none found
     */
    public Set<UUID> getCompanyRegionIds() {
        return idsFor(ScopeLevel.REGION);
    }

    /**
     * Returns the set of company zone IDs extracted from the JWT {@code company_zone_id} claim.
     * The claim may be a single UUID, a comma-separated string, or a JSON array.
     * Whitespace is trimmed, malformed UUIDs are silently skipped, and duplicates are removed.
     *
     * @return an immutable set of parsed UUIDs; empty if none found
     */
    public Set<UUID> getCompanyZoneIds() {
        return idsFor(ScopeLevel.ZONE);
    }

    /**
     * Returns the set of company store IDs extracted from the JWT {@code company_store_id} claim.
     * The claim may be a single UUID, a comma-separated string, or a JSON array.
     * Whitespace is trimmed, malformed UUIDs are silently skipped, and duplicates are removed.
     *
     * @return an immutable set of parsed UUIDs; empty if none found
     */
    public Set<UUID> getCompanyStoreIds() {
        return idsFor(ScopeLevel.STORE);
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-admin} authority.
     * <p>
     * TODO(migration): the legacy {@code ROLE_life-control-admin} realm role is still accepted
     * for backward compatibility while Keycloak is migrated; remove it once all users are
     * provisioned with the {@code lc-admin} client role.
     */
    public boolean isAdmin() {
        if (admin == null) {
            admin = hasRole(Roles.LIFE_CONTROL_ADMIN) || hasRole(Roles.ADMIN);
        }
        return admin;
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_life-control-country} authority.
     * <p>
     * TODO(migration): legacy realm role, retired together with {@code life-control-admin}
     * once Keycloak is fully migrated to {@code lc-*} client roles.
     */
    public boolean isCountryRole() {
        return hasRole(Roles.LIFE_CONTROL_COUNTRY);
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company} authority.
     */
    public boolean hasCompanyRole() {
        return hasRole(Roles.COMPANY);
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company-country} authority.
     */
    public boolean hasCompanyCountryRole() {
        return hasRole(Roles.COMPANY_COUNTRY);
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company-region} authority.
     */
    public boolean hasCompanyRegionRole() {
        return hasRole(Roles.COMPANY_REGION);
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company-zone} authority.
     */
    public boolean hasCompanyZoneRole() {
        return hasRole(Roles.COMPANY_ZONE);
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company-store} authority.
     */
    public boolean hasCompanyStoreRole() {
        return hasRole(Roles.COMPANY_STORE);
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company-country-read} authority.
     */
    public boolean hasCompanyCountryReadRole() {
        return hasRole(Roles.COMPANY_COUNTRY_READ);
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company-region-read} authority.
     */
    public boolean hasCompanyRegionReadRole() {
        return hasRole(Roles.COMPANY_REGION_READ);
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company-zone-read} authority.
     */
    public boolean hasCompanyZoneReadRole() {
        return hasRole(Roles.COMPANY_ZONE_READ);
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company-store-read} authority.
     */
    public boolean hasCompanyStoreReadRole() {
        return hasRole(Roles.COMPANY_STORE_READ);
    }

    /**
     * Verifies that the current user has access to the given company.
     * Admin users have unrestricted access. Non-admin users must have the
     * company ID in their {@code company_id} claim set.
     *
     * @param companyId the company UUID to verify access for
     * @throws AccessDeniedException if access is denied
     */
    public void verifyCompanyAccess(UUID companyId) {
        if (isAdmin()) {
            return;
        }
        verifyLevel(ScopeLevel.COMPANY, companyId);
    }

    /**
     * Access check for CompanyCountry records. The broadest role granted within the
     * company&rarr;country hierarchy determines how far the claim path is verified:
     * {@code lc-company} verifies only the company, while {@code lc-company-country} /
     * {@code lc-company-country-read} also verify the company-country ID.
     *
     * @param companyId        the company UUID to verify (company-level check)
     * @param companyCountryId the company-country UUID to verify (record-level check)
     * @throws AccessDeniedException if access is denied for any reason
     */
    public void verifyCompanyCountryAccess(UUID companyId, UUID companyCountryId) {
        if (isAdmin()) {
            return;
        }
        verifyBroadestGranted(ScopeLevel.COUNTRY, new UUID[] {companyId, companyCountryId}, ScopeLevel.COUNTRY);
    }

    /**
     * Access check for CompanyRegion records. The broadest role granted within the
     * company&rarr;region hierarchy determines how far the claim path is verified.
     *
     * @param companyId        the company UUID to verify (company-level check)
     * @param companyCountryId the company-country UUID to verify (country-level check)
     * @param regionId         the company-region UUID to verify (region-level check)
     * @throws AccessDeniedException if access is denied for any reason
     */
    public void verifyCompanyRegionAccess(UUID companyId, UUID companyCountryId, UUID regionId) {
        if (isAdmin()) {
            return;
        }
        verifyBroadestGranted(ScopeLevel.REGION, new UUID[] {companyId, companyCountryId, regionId}, ScopeLevel.REGION);
    }

    /**
     * Access check for CompanyZone records. The broadest role granted within the
     * company&rarr;zone hierarchy determines how far the claim path is verified.
     *
     * @param companyId        the company UUID to verify (company-level check)
     * @param companyCountryId the company-country UUID to verify (country-level check)
     * @param regionId         the company-region UUID to verify (region-level check)
     * @param zoneId           the company-zone UUID to verify (zone-level check), nullable for create ops
     * @throws AccessDeniedException if access is denied for any reason
     */
    public void verifyCompanyZoneAccess(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId) {
        if (isAdmin()) {
            return;
        }
        verifyBroadestGranted(
                ScopeLevel.ZONE, new UUID[] {companyId, companyCountryId, regionId, zoneId}, ScopeLevel.ZONE);
    }

    /**
     * Access check for CompanyStore records.
     *
     * <p>A store-scoped role ({@code lc-company-store} / {@code lc-company-store-read}) verifies
     * the full company&rarr;store path. Any other authorized role falls back to the
     * company&rarr;zone hierarchy, so broader roles keep their broadest granted scope.</p>
     *
     * @param companyId        the company UUID to verify (company-level check)
     * @param companyCountryId the company-country UUID to verify (country-level check)
     * @param regionId         the company-region UUID to verify (region-level check)
     * @param zoneId           the company-zone UUID to verify (zone-level check)
     * @param storeId          the company-store UUID to verify (store-level check), nullable for create ops
     * @throws AccessDeniedException if access is denied for any reason
     */
    public void verifyCompanyStoreAccess(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {
        if (isAdmin()) {
            return;
        }
        UUID[] ids = {companyId, companyCountryId, regionId, zoneId, storeId};
        if (hasAnyRole(ScopeLevel.STORE)) {
            verifyLevelsUpTo(ScopeLevel.STORE, ids);
            return;
        }
        verifyBroadestGranted(ScopeLevel.ZONE, ids, ScopeLevel.STORE);
    }

    /**
     * Returns the JWT {@code sub} claim — the unique user identifier.
     * Lazily extracted and cached per request.
     *
     * @return the subject claim, or {@code null} if no valid JWT is present
     */
    public String getUserId() {
        if (userId == null) {
            userId = extractClaim("sub");
        }
        return userId;
    }

    /**
     * Returns the JWT {@code preferred_username} claim — the human-readable username.
     * Lazily extracted and cached per request.
     *
     * @return the preferred username, or {@code null} if no valid JWT is present
     */
    public String getUsername() {
        if (username == null) {
            username = extractClaim("preferred_username");
        }
        return username;
    }

    // ── Scope verification ──────────────────────────────────

    /**
     * Verifies the claim path up to the broadest role granted within {@code [COMPANY..maxScope]}.
     * Throws when no role in range grants access.
     */
    private void verifyBroadestGranted(ScopeLevel maxScope, UUID[] ids, ScopeLevel failureTarget) {
        ScopeLevel granted = broadestGrantedScope(maxScope);
        if (granted == null) {
            throw insufficientRole(failureTarget);
        }
        verifyLevelsUpTo(granted, ids);
    }

    /** Returns the broadest role granted within {@code [COMPANY..maxScope]}, or {@code null}. */
    private ScopeLevel broadestGrantedScope(ScopeLevel maxScope) {
        for (ScopeLevel level : ScopeLevel.companyTo(maxScope)) {
            if (hasAnyRole(level)) {
                return level;
            }
        }
        return null;
    }

    private void verifyLevelsUpTo(ScopeLevel maxScope, UUID[] ids) {
        for (ScopeLevel level : ScopeLevel.companyTo(maxScope)) {
            verifyLevel(level, ids[level.ordinal()]);
        }
    }

    private void verifyLevel(ScopeLevel level, UUID id) {
        if (level.isRequired()) {
            if (id == null || !idsFor(level).contains(id)) {
                deny(level, id);
            }
        } else if (id != null && !idsFor(level).contains(id)) {
            deny(level, id);
        }
    }

    private void deny(ScopeLevel level, UUID id) {
        log.warn("Access denied to {} {} for scoped user", level.label(), id);
        throw new AccessDeniedException("Access denied to " + level.label() + ": " + id);
    }

    private AccessDeniedException insufficientRole(ScopeLevel target) {
        log.warn("Access denied: no role grants {} access", target.label());
        return new AccessDeniedException(
                "Insufficient role for " + target.label().replace(' ', '-') + " access");
    }

    // ── Private helpers ──────────────────────────────────────

    private Set<UUID> idsFor(ScopeLevel level) {
        return claimIds.computeIfAbsent(level, l -> extractUuidSetFromClaim(l.claim()));
    }

    private boolean hasAnyRole(ScopeLevel level) {
        return level.roleNames().stream().anyMatch(this::hasRole);
    }

    private boolean hasRole(String roleName) {
        return hasAuthority("ROLE_" + roleName);
    }

    private boolean hasAuthority(String authority) {
        return authorities().contains(authority);
    }

    private Set<String> authorities() {
        if (authorities == null) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            authorities = authentication == null
                    ? Set.of()
                    : authentication.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toUnmodifiableSet());
        }
        return authorities;
    }

    /**
     * Extracts a set of UUIDs from a JWT claim that may be a single UUID string,
     * a comma-separated string, or a JSON array (List).
     * <p>
     * Returns an empty immutable set when the claim is absent, blank, or contains
     * no parseable UUIDs.
     */
    private Set<UUID> extractUuidSetFromClaim(String claimName) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Collections.emptySet();
        }

        var claim = jwt.getClaim(claimName);
        if (claim == null) {
            return Collections.emptySet();
        }

        Stream<String> rawValues;
        if (claim instanceof List<?> list) {
            // JWT claim is a JSON array — e.g. ["id1", "id2"]
            rawValues = list.stream().map(Object::toString).map(String::trim).filter(s -> !s.isEmpty());
        } else {
            // String format: single UUID or comma-separated — e.g. "id1,id2"
            String claimStr = claim.toString().trim();
            if (claimStr.isBlank()) {
                return Collections.emptySet();
            }
            rawValues = Stream.of(claimStr.split(",")).map(String::trim).filter(s -> !s.isEmpty());
        }

        Set<UUID> ids = rawValues
                .map(this::tryParseUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        if (ids.isEmpty()) {
            log.warn("JWT contains {} claim but no valid UUIDs could be parsed: '{}'", claimName, claim);
        }

        return Collections.unmodifiableSet(ids);
    }

    private UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            log.debug("Ignoring malformed UUID in company_id claim: '{}'", value);
            return null;
        }
    }

    /**
     * Extracts a claim value from the JWT principal in the current security context.
     *
     * @param claimName the JWT claim name to extract
     * @return the claim string value, or {@code null} if not available
     */
    private String extractClaim(String claimName) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString(claimName);
        }
        return null;
    }
}
