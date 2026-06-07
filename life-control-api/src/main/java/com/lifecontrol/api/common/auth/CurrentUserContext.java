package com.lifecontrol.api.common.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Request-scoped component that extracts and caches the current user's
 * company IDs and roles from the JWT token in the SecurityContext.
 *
 * <p>The bean uses a scoped proxy so it can be safely injected into
 * singleton-scoped services. Each HTTP request gets its own instance
 * backed by the SecurityContextHolder contents.
 *
 * <p>Company IDs are parsed from the {@code company_id} claim, which may
 * contain a single UUID or multiple comma-separated UUIDs. Malformed
 * values are silently skipped.
 */
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class CurrentUserContext {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserContext.class);

    private static final String CLAIM_COMPANY_COUNTRY_ID = "company_country_id";
    private static final String CLAIM_COMPANY_REGION_ID = "company_region_id";
    private static final String CLAIM_COMPANY_ZONE_ID = "company_zone_id";
    private static final String CLAIM_COMPANY_STORE_ID = "company_store_id";
    static final String ROLE_LC_COMPANY = "ROLE_lc-company";
    static final String ROLE_LC_COMPANY_COUNTRY = "ROLE_lc-company-country";
    static final String ROLE_LC_COMPANY_REGION = "ROLE_lc-company-region";
    static final String ROLE_LC_COMPANY_ZONE = "ROLE_lc-company-zone";
    static final String ROLE_LC_COMPANY_STORE = "ROLE_lc-company-store";

    private Set<UUID> companyIds;
    private Set<UUID> companyCountryIds;
    private Set<UUID> companyRegionIds;
    private Set<UUID> companyZoneIds;
    private Set<UUID> companyStoreIds;
    private Boolean admin;
    private Boolean countryRole;
    private Boolean companyRole;
    private Boolean companyCountryRole;
    private Boolean companyRegionRole;
    private Boolean companyZoneRole;
    private Boolean companyStoreRole;
    private String userId;
    private String username;

    /**
     * Returns the set of company IDs extracted from the JWT {@code company_id} claim.
     * The claim may be a single UUID or comma-separated UUIDs.
     * Whitespace is trimmed, malformed UUIDs are silently skipped, and duplicates are removed.
     *
     * @return an immutable set of parsed UUIDs; empty if none found
     */
    public Set<UUID> getCompanyIds() {
        if (companyIds == null) {
            companyIds = extractCompanyIds();
        }
        return companyIds;
    }

    /**
     * Returns the set of company country IDs extracted from the JWT {@code company_country_id} claim.
     * The claim may be a single UUID or comma-separated UUIDs.
     * Whitespace is trimmed, malformed UUIDs are silently skipped, and duplicates are removed.
     *
     * @return an immutable set of parsed UUIDs; empty if none found
     */
    public Set<UUID> getCompanyCountryIds() {
        if (companyCountryIds == null) {
            companyCountryIds = extractCompanyCountryIds();
        }
        return companyCountryIds;
    }

    /**
     * Returns the set of company region IDs extracted from the JWT {@code company_region_id} claim.
     * The claim may be a single UUID or comma-separated UUIDs.
     * Whitespace is trimmed, malformed UUIDs are silently skipped, and duplicates are removed.
     *
     * @return an immutable set of parsed UUIDs; empty if none found
     */
    public Set<UUID> getCompanyRegionIds() {
        if (companyRegionIds == null) {
            companyRegionIds = extractCompanyRegionIds();
        }
        return companyRegionIds;
    }

    /**
     * Returns the set of company zone IDs extracted from the JWT {@code company_zone_id} claim.
     * The claim may be a single UUID or comma-separated UUIDs.
     * Whitespace is trimmed, malformed UUIDs are silently skipped, and duplicates are removed.
     *
     * @return an immutable set of parsed UUIDs; empty if none found
     */
    public Set<UUID> getCompanyZoneIds() {
        if (companyZoneIds == null) {
            companyZoneIds = extractCompanyZoneIds();
        }
        return companyZoneIds;
    }

    /**
     * Returns the set of company store IDs extracted from the JWT {@code company_store_id} claim.
     * The claim may be a single UUID or comma-separated UUIDs.
     * Whitespace is trimmed, malformed UUIDs are silently skipped, and duplicates are removed.
     *
     * @return an immutable set of parsed UUIDs; empty if none found
     */
    public Set<UUID> getCompanyStoreIds() {
        if (companyStoreIds == null) {
            companyStoreIds = extractCompanyStoreIds();
        }
        return companyStoreIds;
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_life-control-admin} or {@code ROLE_lc-admin} authority.
     */
    public boolean isAdmin() {
        if (admin == null) {
            admin = hasAuthority("ROLE_life-control-admin") || hasAuthority("ROLE_lc-admin");
        }
        return admin;
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_life-control-country} authority.
     */
    public boolean isCountryRole() {
        if (countryRole == null) {
            countryRole = hasAuthority("ROLE_life-control-country");
        }
        return countryRole;
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company} authority.
     */
    public boolean hasCompanyRole() {
        if (companyRole == null) {
            companyRole = hasAuthority(ROLE_LC_COMPANY);
        }
        return companyRole;
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company-country} authority.
     */
    public boolean hasCompanyCountryRole() {
        if (companyCountryRole == null) {
            companyCountryRole = hasAuthority(ROLE_LC_COMPANY_COUNTRY);
        }
        return companyCountryRole;
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company-region} authority.
     */
    public boolean hasCompanyRegionRole() {
        if (companyRegionRole == null) {
            companyRegionRole = hasAuthority(ROLE_LC_COMPANY_REGION);
        }
        return companyRegionRole;
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company-zone} authority.
     */
    public boolean hasCompanyZoneRole() {
        if (companyZoneRole == null) {
            companyZoneRole = hasAuthority(ROLE_LC_COMPANY_ZONE);
        }
        return companyZoneRole;
    }

    /**
     * Returns {@code true} if the current user has the {@code ROLE_lc-company-store} authority.
     */
    public boolean hasCompanyStoreRole() {
        if (companyStoreRole == null) {
            companyStoreRole = hasAuthority(ROLE_LC_COMPANY_STORE);
        }
        return companyStoreRole;
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
        if (companyId == null || !getCompanyIds().contains(companyId)) {
            log.warn("Access denied to company {} for non-admin user", companyId);
            throw new AccessDeniedException("Access denied to company: " + companyId);
        }
    }

    /**
     * Three-tier access check for CompanyCountry records.
     * <ul>
     *   <li>lc-admin: bypasses all checks (returns immediately).</li>
     *   <li>lc-company: delegates to {@link #verifyCompanyAccess(UUID)} for company-level check.</li>
     *   <li>lc-company-country: verifies company access AND that the given
     *       {@code companyCountryId} is present in the {@code company_country_id} JWT claim set.</li>
     * </ul>
     *
     * @param companyId        the company UUID to verify (company-level check)
     * @param companyCountryId the company-country UUID to verify (record-level check)
     * @throws AccessDeniedException if access is denied for any reason
     */
    public void verifyCompanyCountryAccess(UUID companyId, UUID companyCountryId) {
        if (isAdmin()) {
            return;
        }
        if (hasCompanyRole()) {
            verifyCompanyAccess(companyId);
            return;
        }
        if (hasCompanyCountryRole()) {
            verifyCompanyAccess(companyId);
            if (companyCountryId == null || !getCompanyCountryIds().contains(companyCountryId)) {
                log.warn("Access denied to company-country {} for lc-company-country user", companyCountryId);
                throw new AccessDeniedException("Access denied to company country: " + companyCountryId);
            }
            return;
        }
        throw new AccessDeniedException("Insufficient role for company-country access");
    }

    /**
     * Four-tier access check for CompanyRegion records.
     * <ul>
     *   <li>lc-admin: bypasses all checks (returns immediately).</li>
     *   <li>lc-company: delegates to {@link #verifyCompanyAccess(UUID)} for company-level check.</li>
     *   <li>lc-company-country: verifies company access AND that the given
     *       {@code companyCountryId} is present in the {@code company_country_id} JWT claim set.</li>
     *   <li>lc-company-region: verifies company access AND that the given
     *       {@code companyCountryId} is present in the {@code company_country_id} claim set AND
     *       that the given {@code regionId} is present in the {@code company_region_id} claim set.</li>
     * </ul>
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
        if (hasCompanyRole()) {
            verifyCompanyAccess(companyId);
            return;
        }
        if (hasCompanyCountryRole()) {
            verifyCompanyAccess(companyId);
            if (companyCountryId == null || !getCompanyCountryIds().contains(companyCountryId)) {
                log.warn("Access denied to company-country {} for lc-company-country user", companyCountryId);
                throw new AccessDeniedException("Access denied to company country: " + companyCountryId);
            }
            return;
        }
        if (hasCompanyRegionRole()) {
            verifyCompanyAccess(companyId);
            if (companyCountryId == null || !getCompanyCountryIds().contains(companyCountryId)) {
                log.warn("Access denied to company-country {} for lc-company-region user", companyCountryId);
                throw new AccessDeniedException("Access denied to company country: " + companyCountryId);
            }
            if (regionId != null && !getCompanyRegionIds().contains(regionId)) {
                log.warn("Access denied to company-region {} for lc-company-region user", regionId);
                throw new AccessDeniedException("Access denied to company region: " + regionId);
            }
            return;
        }
        throw new AccessDeniedException("Insufficient role for company-region access");
    }

    /**
     * Five-tier access check for CompanyZone records.
     * <ul>
     *   <li>lc-admin: bypasses all checks (returns immediately).</li>
     *   <li>lc-company: delegates to {@link #verifyCompanyAccess(UUID)} for company-level check.</li>
     *   <li>lc-company-country: verifies company access AND that the given
     *       {@code companyCountryId} is present in the {@code company_country_id} JWT claim set.</li>
     *   <li>lc-company-region: verifies company access AND that the given
     *       {@code companyCountryId} is present in the {@code company_country_id} claim set AND
     *       that the given {@code regionId} is present in the {@code company_region_id} claim set.</li>
     *   <li>lc-company-zone: verifies company access AND country AND region AND that the given
     *       {@code zoneId} is present in the {@code company_zone_id} claim set.</li>
     * </ul>
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
        if (hasCompanyRole()) {
            verifyCompanyAccess(companyId);
            return;
        }
        if (hasCompanyCountryRole()) {
            verifyCompanyAccess(companyId);
            if (companyCountryId == null || !getCompanyCountryIds().contains(companyCountryId)) {
                log.warn("Access denied to company-country {} for lc-company-country user", companyCountryId);
                throw new AccessDeniedException("Access denied to company country: " + companyCountryId);
            }
            return;
        }
        if (hasCompanyRegionRole()) {
            verifyCompanyAccess(companyId);
            if (companyCountryId == null || !getCompanyCountryIds().contains(companyCountryId)) {
                log.warn("Access denied to company-country {} for lc-company-region user", companyCountryId);
                throw new AccessDeniedException("Access denied to company country: " + companyCountryId);
            }
            if (regionId != null && !getCompanyRegionIds().contains(regionId)) {
                log.warn("Access denied to company-region {} for lc-company-region user", regionId);
                throw new AccessDeniedException("Access denied to company region: " + regionId);
            }
            return;
        }
        if (hasCompanyZoneRole()) {
            verifyCompanyAccess(companyId);
            if (companyCountryId == null || !getCompanyCountryIds().contains(companyCountryId)) {
                log.warn("Access denied to company-country {} for lc-company-zone user", companyCountryId);
                throw new AccessDeniedException("Access denied to company country: " + companyCountryId);
            }
            if (regionId != null && !getCompanyRegionIds().contains(regionId)) {
                log.warn("Access denied to company-region {} for lc-company-zone user", regionId);
                throw new AccessDeniedException("Access denied to company region: " + regionId);
            }
            if (zoneId != null && !getCompanyZoneIds().contains(zoneId)) {
                log.warn("Access denied to company-zone {} for lc-company-zone user", zoneId);
                throw new AccessDeniedException("Access denied to company zone: " + zoneId);
            }
            return;
        }
        throw new AccessDeniedException("Insufficient role for company-zone access");
    }

    /**
     * Six-tier access check for CompanyStore records.
     * <ul>
     *   <li>lc-admin: bypasses all checks (returns immediately).</li>
     *   <li>lc-company: delegates to {@link #verifyCompanyAccess(UUID)} for company-level check.</li>
     *   <li>lc-company-country: verifies company access AND that the given
     *       {@code companyCountryId} is present in the {@code company_country_id} JWT claim set.</li>
     *   <li>lc-company-region: verifies company access AND country AND that the given
     *       {@code regionId} is present in the {@code company_region_id} claim set.</li>
     *   <li>lc-company-zone: verifies company access AND country AND region AND that the given
     *       {@code zoneId} is present in the {@code company_zone_id} claim set.</li>
     *   <li>lc-company-store: verifies company access AND country AND region AND zone AND that the given
     *       {@code storeId} is present in the {@code company_store_id} claim set.</li>
     * </ul>
     *
     * @param companyId        the company UUID to verify (company-level check)
     * @param companyCountryId the company-country UUID to verify (country-level check)
     * @param regionId         the company-region UUID to verify (region-level check)
     * @param zoneId           the company-zone UUID to verify (zone-level check)
     * @param storeId          the company-store UUID to verify (store-level check), nullable for create ops
     * @throws AccessDeniedException if access is denied for any reason
     */
    public void verifyCompanyStoreAccess(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {
        if (isAdmin()) {
            return;
        }
        if (hasCompanyStoreRole()) {
            verifyCompanyAccess(companyId);
            if (companyCountryId == null || !getCompanyCountryIds().contains(companyCountryId)) {
                log.warn("Access denied to company-country {} for lc-company-store user", companyCountryId);
                throw new AccessDeniedException("Access denied to company country: " + companyCountryId);
            }
            if (regionId != null && !getCompanyRegionIds().contains(regionId)) {
                log.warn("Access denied to company-region {} for lc-company-store user", regionId);
                throw new AccessDeniedException("Access denied to company region: " + regionId);
            }
            if (zoneId != null && !getCompanyZoneIds().contains(zoneId)) {
                log.warn("Access denied to company-zone {} for lc-company-store user", zoneId);
                throw new AccessDeniedException("Access denied to company zone: " + zoneId);
            }
            if (storeId != null && !getCompanyStoreIds().contains(storeId)) {
                log.warn("Access denied to company-store {} for lc-company-store user", storeId);
                throw new AccessDeniedException("Access denied to company store: " + storeId);
            }
            return;
        }
        if (hasCompanyZoneRole()) {
            verifyCompanyZoneAccess(companyId, companyCountryId, regionId, zoneId);
            return;
        }
        if (hasCompanyRegionRole()) {
            verifyCompanyRegionAccess(companyId, companyCountryId, regionId);
            return;
        }
        if (hasCompanyCountryRole()) {
            verifyCompanyCountryAccess(companyId, companyCountryId);
            return;
        }
        if (hasCompanyRole()) {
            verifyCompanyAccess(companyId);
            return;
        }
        throw new AccessDeniedException("Insufficient role for company-store access");
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

    // ── Private helpers ──────────────────────────────────────

    private Set<UUID> extractCompanyIds() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Collections.emptySet();
        }

        String companyIdClaim = jwt.getClaimAsString("company_id");
        if (companyIdClaim == null || companyIdClaim.isBlank()) {
            return Collections.emptySet();
        }

        Set<UUID> ids = Stream.of(companyIdClaim.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::tryParseUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        if (ids.isEmpty()) {
            log.warn("JWT contains company_id claim but no valid UUIDs could be parsed: '{}'",
                    companyIdClaim);
        }

        return Collections.unmodifiableSet(ids);
    }

    private Set<UUID> extractCompanyCountryIds() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Collections.emptySet();
        }

        String claim = jwt.getClaimAsString(CLAIM_COMPANY_COUNTRY_ID);
        if (claim == null || claim.isBlank()) {
            return Collections.emptySet();
        }

        Set<UUID> ids = Stream.of(claim.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::tryParseUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        if (ids.isEmpty()) {
            log.warn("JWT contains company_country_id claim but no valid UUIDs could be parsed: '{}'",
                    claim);
        }

        return Collections.unmodifiableSet(ids);
    }

    private Set<UUID> extractCompanyRegionIds() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Collections.emptySet();
        }

        String claim = jwt.getClaimAsString(CLAIM_COMPANY_REGION_ID);
        if (claim == null || claim.isBlank()) {
            return Collections.emptySet();
        }

        Set<UUID> ids = Stream.of(claim.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::tryParseUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        if (ids.isEmpty()) {
            log.warn("JWT contains company_region_id claim but no valid UUIDs could be parsed: '{}'",
                    claim);
        }

        return Collections.unmodifiableSet(ids);
    }

    private Set<UUID> extractCompanyZoneIds() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Collections.emptySet();
        }

        String claim = jwt.getClaimAsString(CLAIM_COMPANY_ZONE_ID);
        if (claim == null || claim.isBlank()) {
            return Collections.emptySet();
        }

        Set<UUID> ids = Stream.of(claim.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::tryParseUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        if (ids.isEmpty()) {
            log.warn("JWT contains company_zone_id claim but no valid UUIDs could be parsed: '{}'",
                    claim);
        }

        return Collections.unmodifiableSet(ids);
    }

    private Set<UUID> extractCompanyStoreIds() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Collections.emptySet();
        }

        String claim = jwt.getClaimAsString(CLAIM_COMPANY_STORE_ID);
        if (claim == null || claim.isBlank()) {
            return Collections.emptySet();
        }

        Set<UUID> ids = Stream.of(claim.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::tryParseUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        if (ids.isEmpty()) {
            log.warn("JWT contains company_store_id claim but no valid UUIDs could be parsed: '{}'",
                    claim);
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

    private boolean hasAuthority(String authority) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
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
