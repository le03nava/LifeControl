package com.lifecontrol.api.profile;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.profile.dto.ProfileResponse;
import com.lifecontrol.api.profile.dto.ProfileUpdateRequest;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.model.UserPreferences;
import com.lifecontrol.api.usersadmin.repository.UserPreferencesRepository;
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

    public ProfileService(CurrentUserContext currentUserContext,
                          IdentityProvider identityProvider,
                          UserPreferencesRepository userPreferencesRepository) {
        this.currentUserContext = currentUserContext;
        this.identityProvider = identityProvider;
        this.userPreferencesRepository = userPreferencesRepository;
    }

    /**
     * Returns the authenticated user's combined profile: basic info from the
     * JWT token and location preferences from the {@code user_preferences} table.
     * If no preferences row exists yet, one is created automatically.
     */
    public ProfileResponse getProfile() {
        var userId = currentUserContext.getUserId();
        var username = currentUserContext.getUsername();

        var claims = extractJwtClaims();

        var preferences = userPreferencesRepository.findByKeycloakUserId(userId)
                .orElseGet(() -> {
                    log.info("No preferences row for user {}, creating empty one", userId);
                    var newPrefs = UserPreferences.builder()
                            .keycloakUserId(userId)
                            .build();
                    return userPreferencesRepository.save(newPrefs);
                });

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
                preferences.getCompanyStoreId()
        );
    }

    /**
     * Updates the authenticated user's profile.
     *
     * <p>Keycloak is updated first (name/email); then the local
     * {@code user_preferences} row is created or updated. If the Keycloak
     * call fails, the preferences are NOT saved (best-effort consistency).
     */
    public ProfileResponse updateProfile(ProfileUpdateRequest request) {
        var userId = currentUserContext.getUserId();
        var username = currentUserContext.getUsername();

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
        var preferences = userPreferencesRepository.findByKeycloakUserId(userId)
                .orElseGet(() -> UserPreferences.builder()
                        .keycloakUserId(userId)
                        .build());

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
                preferences.getCompanyStoreId()
        );
    }

    // ── Private helpers ──────────────────────────────────────

    private JwtClaims extractJwtClaims() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return new JwtClaims(
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("given_name"),
                    jwt.getClaimAsString("family_name")
            );
        }
        return new JwtClaims(null, null, null);
    }

    private record JwtClaims(String email, String firstName, String lastName) {}
}
