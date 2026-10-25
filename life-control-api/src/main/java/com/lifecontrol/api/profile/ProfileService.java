package com.lifecontrol.api.profile;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.exception.ResourceNotFoundException;
import com.lifecontrol.api.hr.dto.ScopeAssignmentResponse;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.service.EmployeeScopeAssignmentService;
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
 * the local {@code user_preferences} table (the store preference).
 */
@Service
@Transactional
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final CurrentUserContext currentUserContext;
    private final IdentityProvider identityProvider;
    private final UserPreferencesRepository userPreferencesRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeScopeAssignmentService employeeScopeAssignmentService;
    private final ProfileLocationResolver locationResolver;

    public ProfileService(
            CurrentUserContext currentUserContext,
            IdentityProvider identityProvider,
            UserPreferencesRepository userPreferencesRepository,
            EmployeeRepository employeeRepository,
            EmployeeScopeAssignmentService employeeScopeAssignmentService,
            ProfileLocationResolver locationResolver) {
        this.currentUserContext = currentUserContext;
        this.identityProvider = identityProvider;
        this.userPreferencesRepository = userPreferencesRepository;
        this.employeeRepository = employeeRepository;
        this.employeeScopeAssignmentService = employeeScopeAssignmentService;
        this.locationResolver = locationResolver;
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
     *
     * <p>{@code location} is derived from that <b>resolved</b> value and never from the stored column,
     * so the two can never disagree: it is the store's own chain, read through the authorized store
     * path, and it is {@code null} whenever there is nothing to derive it from or the store can no
     * longer be read (S1/S2). A missing {@code location} is a degraded preselect, never a failed read —
     * see {@link ProfileLocationResolver}.</p>
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

        return toProfileResponse(userId, username, claims, preferences.getCompanyStoreId(), assignedStores);
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
     *
     * <p>The store preference is written by one of three rules (S4): {@code clearStorePreference}
     * clears the column, a non-null {@code companyStoreId} sets it, and neither leaves it untouched.
     * The third is what an omitted value already did, and it is pinned so the screen's «— None —»
     * cannot silently restore the stored store the way it did while the column was unclearable. Asking
     * to clear and to set at once is a contradiction the request itself carries, so it is refused
     * <b>first</b>, before the assignment read and before Keycloak (S5).</p>
     *
     * <p><b>What this path deliberately does not write</b> (T14 of {@code company-scope-local-fallback}):
     * the four tenancy ancestors. They were copied straight from the request with no validation of any
     * kind — which is what made the profile screen the escalation that record exists to remove — and
     * since the access projection materialises the chain in Keycloak (D1 = A) there is nothing left for
     * a subject to assert here. The columns stay in the row (T18); this method simply stops filling
     * them.</p>
     */
    public ProfileResponse updateProfile(ProfileUpdateRequest request) {
        var userId = currentUserContext.getUserId();
        var username = currentUserContext.getUsername();

        // 0. Refuse a request that contradicts itself before anything is read or written (S5/T24):
        //    clearing the preference and setting it are different answers to the same question, and a
        //    request that carries both has no answer this service may choose on the caller's behalf.
        verifyStorePreferenceIsCoherent(request);

        // 1. Resolve the caller's own current assignments once: it serves both the refusal and the
        //    response's resolved store preference (D10/T24).
        var assignedStores = resolveAssignedStores(userId);
        verifyStoreIsAssigned(request.companyStoreId(), assignedStores);

        // 2. Update Keycloak if any basic-info field was provided
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

        // 3. Create or update user_preferences. The store preference is the only location column this
        //    path still writes (T14 of `company-scope-local-fallback`): the four tenancy ancestors are
        //    authorization inputs, and the subject of that decision is the last party who may assert
        //    one, so the screen stopped writing them and this service followed. The columns survive in
        //    the entity (T18) and nothing reads them for authorization (E10).
        var preferences = userPreferencesRepository
                .findByKeycloakUserId(userId)
                .orElseGet(
                        () -> UserPreferences.builder().keycloakUserId(userId).build());

        if (Boolean.TRUE.equals(request.clearStorePreference())) {
            preferences.setCompanyStoreId(null);
        } else if (request.companyStoreId() != null) {
            preferences.setCompanyStoreId(request.companyStoreId());
        }

        userPreferencesRepository.save(preferences);

        // 4. Build response — basic info still comes from the JWT
        //    (Keycloak changes won't reflect until the next token refresh)
        var claims = extractJwtClaims();

        return toProfileResponse(userId, username, claims, preferences.getCompanyStoreId(), assignedStores);
    }

    // ── Private helpers ──────────────────────────────────────

    /**
     * The current store assignments of the caller's own employee row, or {@link Optional#empty()} when
     * the caller has no employee row at all (D11).
     *
     * <p>It reads the same single source as the five {@code company_*} claims: the store is the fourth
     * node column of {@code employee_scope_assignments}, so there is no second table to consult and the
     * profile cannot show a store the derivation would not grant (S10). The rows are the scope service's
     * store rows covering today, in the deterministic order its covering-date finder returns, and the
     * mapping to {@link ProfileResponse.AssignedStore} below keeps that order.</p>
     *
     * <p>The employee is resolved by the caller's own {@code keycloak_user_id}, never by an id from
     * the request, so this read can only ever reach the caller's own assignments (T24) — which is why
     * it stays <b>scope-free</b>: demanding a company claim would refuse the very caller the profile
     * screen serves, and it is by construction a self-scoped read. The
     * {@code Optional} is load-bearing: {@code empty} means <b>unconstrained</b> and must not be
     * confused with an empty list, which means <b>constrained to exactly none</b>.</p>
     */
    private Optional<List<ScopeAssignmentResponse>> resolveAssignedStores(String userId) {
        return employeeRepository
                .findByKeycloakUserId(userId)
                .map(employee -> employeeScopeAssignmentService.getCurrentStoreAssignments(employee.getId()));
    }

    /**
     * S5: {@code clearStorePreference} and a non-null {@code companyStoreId} are two different answers
     * to the same question, so the request is refused with the module's 400 idiom
     * ({@link IllegalArgumentException}, which {@code GlobalExceptionHandler} maps to 400) instead of
     * letting one rule silently win. It runs before the assignment read and before Keycloak (T24), so a
     * refused request changes neither the identity nor the preference row.
     */
    private static void verifyStorePreferenceIsCoherent(ProfileUpdateRequest request) {
        if (Boolean.TRUE.equals(request.clearStorePreference()) && request.companyStoreId() != null) {
            throw new IllegalArgumentException("clearStorePreference cannot be combined with companyStoreId");
        }
    }

    /**
     * D11: with no employee row the constraint does not exist, so any store is accepted. Otherwise the
     * requested store must be in the current assignment set, and a miss is the 404 of T23 — produced by
     * {@link ResourceNotFoundException}, whose message names the <b>store</b> and the fact, never a
     * store as an assignment id.
     */
    private static void verifyStoreIsAssigned(
            UUID requestedStoreId, Optional<List<ScopeAssignmentResponse>> assignedStores) {
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
            UUID storedStoreId, Optional<List<ScopeAssignmentResponse>> assignedStores) {
        if (assignedStores.isEmpty() || storedStoreId == null) {
            return storedStoreId;
        }
        var covered =
                assignedStores.get().stream().anyMatch(assignment -> storedStoreId.equals(assignment.companyStoreId()));
        return covered ? storedStoreId : null;
    }

    /**
     * The one place a {@link ProfileResponse} is assembled, so the read and the update cannot drift
     * apart: both resolve the stored column through the same rule (D10) and derive {@code location}
     * from that resolved value through the same read (S1/S2).
     */
    private ProfileResponse toProfileResponse(
            String userId,
            String username,
            JwtClaims claims,
            UUID storedStoreId,
            Optional<List<ScopeAssignmentResponse>> assignedStores) {
        var resolvedStoreId = resolveStorePreference(storedStoreId, assignedStores);
        return new ProfileResponse(
                userId,
                username,
                claims.email(),
                claims.firstName(),
                claims.lastName(),
                resolvedStoreId,
                assignedStores.map(ProfileService::toAssignedStores).orElse(null),
                locationResolver.resolve(resolvedStoreId));
    }

    /**
     * Projects T14's assignment rows into the response's own record; the chain walk stays in HR (T25).
     *
     * <p>A scope row carries one of four levels and this response only describes stores, so a non-store
     * row is dropped rather than rendered as an assignment its shape cannot express. The store rows come
     * from the store-only finder and keep its order.</p>
     */
    private static List<ProfileResponse.AssignedStore> toAssignedStores(List<ScopeAssignmentResponse> assignments) {
        return assignments.stream()
                .filter(assignment -> assignment.level() == ScopeLevel.STORE)
                .map(ProfileService::toAssignedStore)
                .toList();
    }

    private static ProfileResponse.AssignedStore toAssignedStore(ScopeAssignmentResponse assignment) {
        var derived = assignment.derived();
        return new ProfileResponse.AssignedStore(
                assignment.companyStoreId(),
                assignment.nodeName(),
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
