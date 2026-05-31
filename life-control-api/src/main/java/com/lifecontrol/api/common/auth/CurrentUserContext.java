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

    private Set<UUID> companyIds;
    private Boolean admin;
    private Boolean countryRole;
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
     * Returns {@code true} if the current user has the {@code ROLE_life-control-admin} authority.
     */
    public boolean isAdmin() {
        if (admin == null) {
            admin = hasAuthority("ROLE_life-control-admin");
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
