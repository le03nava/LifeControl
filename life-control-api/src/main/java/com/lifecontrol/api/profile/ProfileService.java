package com.lifecontrol.api.profile;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.exception.ResourceNotFoundException;
import com.lifecontrol.api.hr.dto.StoreAssignmentResponse;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.service.EmployeeStoreAssignmentService;
import com.lifecontrol.api.profile.dto.ProfileResponse;
import com.lifecontrol.api.profile.dto.ProfileUpdateRequest;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.model.UserPreferences;
import com.lifecontrol.api.usersadmin.repository.UserPreferencesRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates profile read/update across Keycloak (identity) and
 * the local {@code user_preferences} table (location hierarchy).
 */
@Service
@Transactional
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final CurrentUserContext currentUserContext;
    private final IdentityProvider identityProvider;
    private final UserPreferencesRepository userPreferencesRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeStoreAssignmentService employeeStoreAssignmentService;

    public ProfileService(
            CurrentUserContext currentUserContext,
            IdentityProvider identityProvider,
            UserPreferencesRepository userPreferencesRepository,
            EmployeeRepository employeeRepository,
            EmployeeStoreAssignmentService employeeStoreAssignmentService) {
        this.currentUserContext = currentUserContext;
        this.identityProvider = identityProvider;
        this.userPreferencesRepository = userPreferencesRepository;
        this.employeeRepository = employeeRepository;
        this.employeeStoreAssignmentService = employeeStoreAssignmentService;
    }

    /**
     * Returns the authenticated user's combined profile: basic info from the
     * JWT token and location preferences from the {@code user_preferences} table.
     * If no preferences row exists yet, one is created automatically.
     *
     * <p>The stored store preference is resolved on read (D10): it is returned only while a store
     * assignment covering today still exists, and {@code null} otherwise. Nothing is written by the
     * read, so the stale value stays in the column and becomes a true answer again if that same store
     * is reassigned. A caller with no employee row is unconstrained (D11); {@code assignedStores} is
     * {@code null} to say so.</p>
     */
    public ProfileResponse getProfile() {
        var userId = currentUserContext.getUserId();
        var username = currentUserContext.getUsername();

        var claims = extractJwtClaims();

        var preferences = userPreferencesRepository.findByKeycloakUserId(userId).orElseGet(() -> {
            log.info("No preferences row for user {}, creating empty one", userId);
            var newPrefs = UserPreferences.builder().keycloakUserId(userId).build();
            return userPreferencesRepository.save(newPrefs);
        });

        var assignedStores = resolveAssignedStores(userId);

        return new ProfileResponse(
                userId,
                username,
                claims.email(),
                claims.firstName(),
                claims.lastName(),
                preferences.getCompanyCountryId(),
                preferences.getCompanyId(),
                preferences.getCompanyRegionId(),
                preferences.getCompanyZoneId(),
                resolveStorePreference(preferences.getCompanyStoreId(), assignedStores),
                assignedStores.map(ProfileService::toAssignedStores).orElse(null));
    }

    /**
     * Updates the authenticated user's profile.
     *
     * <p>Keycloak is updated first (name/email); then the local
     * {@code user_preferences} row is created or updated. If the Keycloak
     * call fails, the preferences are NOT saved (best-effort consistency).</p>
     *
     * <p>The store refusal happens <b>before</b> Keycloak is touched (T24): Keycloak is updated
     * before the preferences row, so validating at the preference step would leave a refused request
     * with the name and email already changed. The refusal and the effective-value rule both come
     * from the same single read of the current assignments.</p>
     */
    public ProfileResponse updateProfile(ProfileUpdateRequest request) {
        var userId = currentUserContext.getUserId();
        var username = currentUserContext.getUsername();

        // 0. Resolve the caller's own current assignments once: it serves both the refusal and the
        //    response's resolved store preference (D10/T24).
        var assignedStores = resolveAssignedStores(userId);
        verifyStoreIsAssigned(request.companyStoreId(), assignedStores);

        // 1. Update Keycloak if any basic-info field was provided
        if (request.firstName() != null || request.lastName() != null || request.email() != null) {
            var userRep = new UserRepresentation();
            if (request.firstName() != null) {
                userRep.setFirstName(request.firstName());
            }
            if (request.lastName() != null) {
                userRep.setLastName(request.lastName());
            }
            if (request.email() != null) {
                userRep.setEmail(request.email());
            }
            identityProvider.updateUser(userId, userRep);
        }

        // 2. Create or update user_preferences
        var preferences = userPreferencesRepository
                .findByKeycloakUserId(userId)
                .orElseGet(
                        () -> UserPreferences.builder().keycloakUserId(userId).build());

        if (request.companyCountryId() != null) {
            preferences.setCompanyCountryId(request.companyCountryId());
        }
        if (request.companyId() != null) {
            preferences.setCompanyId(request.companyId());
        }
        if (request.companyRegionId() != null) {
            preferences.setCompanyRegionId(request.companyRegionId());
        }
        if (request.companyZoneId() != null) {
            preferences.setCompanyZoneId(request.companyZoneId());
        }
        if (request.companyStoreId() != null) {
            preferences.setCompanyStoreId(request.companyStoreId());
        }

        userPreferencesRepository.save(preferences);

        // 3. Build response — basic info still comes from the JWT
        //    (Keycloak changes won't reflect until the next token refresh)
        var claims = extractJwtClaims();

        return new ProfileResponse(
                userId,
                username,
                claims.email(),
                claims.firstName(),
                claims.lastName(),
                preferences.getCompanyCountryId(),
                preferences.getCompanyId(),
                preferences.getCompanyRegionId(),
                preferences.getCompanyZoneId(),
                resolveStorePreference(preferences.getCompanyStoreId(), assignedStores),
                assignedStores.map(ProfileService::toAssignedStores).orElse(null));
    }

    // ── Private helpers ──────────────────────────────────────

    /**
     * The current assignment set of the caller's own employee row, or {@link Optional#empty()} when
     * the caller has no employee row at all (D11).
     *
     * <p>The employee is resolved by the caller's own {@code keycloak_user_id}, never by an id from
     * the request, so this read can only ever reach the caller's own assignments (T24). The
     * {@code Optional} is load-bearing: {@code empty} means <b>unconstrained</b> and must not be
     * confused with an empty list, which means <b>constrained to exactly none</b>.</p>
     */
    private Optional<List<StoreAssignmentResponse>> resolveAssignedStores(String userId) {
        return employeeRepository
                .findByKeycloakUserId(userId)
                .map(employee -> employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(employee.getId()));
    }

    /**
     * D11: with no employee row the constraint does not exist, so any store is accepted. Otherwise the
     * requested store must be in the current assignment set, and a miss is the 404 of T23 — produced by
     * {@link ResourceNotFoundException}, whose message names the <b>store</b> and the fact, never a
     * store as an assignment id.
     */
    private static void verifyStoreIsAssigned(
            UUID requestedStoreId, Optional<List<StoreAssignmentResponse>> assignedStores) {
        if (requestedStoreId == null || assignedStores.isEmpty()) {
            return;
        }
        var covered = assignedStores.get().stream()
                .anyMatch(assignment -> requestedStoreId.equals(assignment.companyStoreId()));
        if (!covered) {
            throw new ResourceNotFoundException(
                    "No current store assignment for the authenticated employee covers store " + requestedStoreId);
        }
    }

    /**
     * D10: the stored preference is a pointer resolved through the assignment set. With no employee
     * row it is returned as-is (D11); otherwise it survives only while a current assignment covers it,
     * and is {@code null} when it does not. The stored column is never rewritten by a read.
     */
    private static UUID resolveStorePreference(
            UUID storedStoreId, Optional<List<StoreAssignmentResponse>> assignedStores) {
        if (assignedStores.isEmpty() || storedStoreId == null) {
            return storedStoreId;
        }
        var covered =
                assignedStores.get().stream().anyMatch(assignment -> storedStoreId.equals(assignment.companyStoreId()));
        return covered ? storedStoreId : null;
    }

    /** Projects T14's assignment rows into the response's own record; the chain walk stays in HR (T25). */
    private static List<ProfileResponse.AssignedStore> toAssignedStores(List<StoreAssignmentResponse> assignments) {
        return assignments.stream().map(ProfileService::toAssignedStore).toList();
    }

    private static ProfileResponse.AssignedStore toAssignedStore(StoreAssignmentResponse assignment) {
        var derived = assignment.derived();
        return new ProfileResponse.AssignedStore(
                assignment.companyStoreId(),
                assignment.companyStoreName(),
                derived.companyId(),
                derived.companyName(),
                derived.companyCountryId(),
                derived.companyCountryName(),
                derived.companyRegionId(),
                derived.companyRegionName(),
                derived.companyZoneId(),
                derived.companyZoneName());
    }

    private JwtClaims extractJwtClaims() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return new JwtClaims(
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("given_name"),
                    jwt.getClaimAsString("family_name"));
        }
        return new JwtClaims(null, null, null);
    }

    private record JwtClaims(String email, String firstName, String lastName) {}
}
