package com.lifecontrol.api.profile;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.profile.dto.ProfileUpdateRequest;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.model.UserPreferences;
import com.lifecontrol.api.usersadmin.repository.UserPreferencesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileService Tests")
class ProfileServiceTest {

    private static final String USER_ID = "keycloak-user-id-123";
    private static final String USERNAME = "jdoe";
    private static final String EMAIL = "jdoe@example.com";
    private static final String FIRST_NAME = "John";
    private static final String LAST_NAME = "Doe";

    @Mock
    private CurrentUserContext currentUserContext;
    @Mock
    private IdentityProvider identityProvider;
    @Mock
    private UserPreferencesRepository userPreferencesRepository;
    @Mock
    private SecurityContext securityContext;
    @Mock
    private Authentication authentication;
    @Mock
    private Jwt jwt;

    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(currentUserContext, identityProvider, userPreferencesRepository);

        when(currentUserContext.getUserId()).thenReturn(USER_ID);
        when(currentUserContext.getUsername()).thenReturn(USERNAME);

        // Stub JWT extraction for claims
        SecurityContextHolder.setContext(securityContext);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getClaimAsString("email")).thenReturn(EMAIL);
        when(jwt.getClaimAsString("given_name")).thenReturn(FIRST_NAME);
        when(jwt.getClaimAsString("family_name")).thenReturn(LAST_NAME);
    }

    // ── getProfile ─────────────────────────────────────────────

    @Nested
    @DisplayName("getProfile")
    class GetProfileTests {

        @Test
        @DisplayName("should return profile with existing preferences")
        void shouldReturnProfileWithExistingPreferences() {
            var countryId = UUID.randomUUID();
            var companyId = UUID.randomUUID();
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyCountryId(countryId)
                    .companyId(companyId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID))
                    .thenReturn(Optional.of(prefs));

            var result = profileService.getProfile();

            assertThat(result.keycloakUserId()).isEqualTo(USER_ID);
            assertThat(result.username()).isEqualTo(USERNAME);
            assertThat(result.email()).isEqualTo(EMAIL);
            assertThat(result.firstName()).isEqualTo(FIRST_NAME);
            assertThat(result.lastName()).isEqualTo(LAST_NAME);
            assertThat(result.companyCountryId()).isEqualTo(countryId);
            assertThat(result.companyId()).isEqualTo(companyId);
        }

        @Test
        @DisplayName("should create empty preferences when none exist")
        void shouldCreateEmptyPreferencesWhenNoneExist() {
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID))
                    .thenReturn(Optional.empty());
            var savedPrefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .build();
            when(userPreferencesRepository.save(any(UserPreferences.class)))
                    .thenReturn(savedPrefs);

            var result = profileService.getProfile();

            assertThat(result.keycloakUserId()).isEqualTo(USER_ID);
            assertThat(result.companyCountryId()).isNull();
            assertThat(result.companyId()).isNull();

            var captor = ArgumentCaptor.forClass(UserPreferences.class);
            verify(userPreferencesRepository).save(captor.capture());
            assertThat(captor.getValue().getKeycloakUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("should handle null JWT claims gracefully")
        void shouldHandleNullJwtClaims() {
            when(jwt.getClaimAsString("email")).thenReturn(null);
            when(jwt.getClaimAsString("given_name")).thenReturn(null);
            when(jwt.getClaimAsString("family_name")).thenReturn(null);

            var prefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID))
                    .thenReturn(Optional.of(prefs));

            var result = profileService.getProfile();

            assertThat(result.email()).isNull();
            assertThat(result.firstName()).isNull();
            assertThat(result.lastName()).isNull();
        }
    }

    // ── updateProfile ──────────────────────────────────────────

    @Nested
    @DisplayName("updateProfile")
    class UpdateProfileTests {

        @Test
        @DisplayName("should update Keycloak and preferences when all fields provided")
        void shouldUpdateKeycloakAndPreferences() {
            var countryId = UUID.randomUUID();
            var request = new ProfileUpdateRequest("NewFirst", "NewLast",
                    "new@example.com", countryId, null, null, null, null);

            var prefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID))
                    .thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class)))
                    .thenReturn(prefs);

            var result = profileService.updateProfile(request);

            verify(identityProvider).updateUser(any(String.class), any());
            verify(userPreferencesRepository).save(any(UserPreferences.class));
            assertThat(result.companyCountryId()).isEqualTo(countryId);
        }

        @Test
        @DisplayName("should skip Keycloak update when no identity fields provided")
        void shouldSkipKeycloakUpdateWhenNoIdentityFields() {
            var countryId = UUID.randomUUID();
            var request = new ProfileUpdateRequest(null, null, null,
                    countryId, null, null, null, null);

            var prefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID))
                    .thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class)))
                    .thenReturn(prefs);

            profileService.updateProfile(request);

            verify(identityProvider, never()).updateUser(any(), any());
            verify(userPreferencesRepository).save(any(UserPreferences.class));
        }

        @Test
        @DisplayName("should create preferences row when none exists on update")
        void shouldCreatePreferencesWhenNoneExists() {
            var countryId = UUID.randomUUID();
            var request = new ProfileUpdateRequest("First", null, null,
                    countryId, null, null, null, null);

            when(userPreferencesRepository.findByKeycloakUserId(USER_ID))
                    .thenReturn(Optional.empty());

            var savedPrefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyCountryId(countryId)
                    .build();
            when(userPreferencesRepository.save(any(UserPreferences.class)))
                    .thenReturn(savedPrefs);

            var result = profileService.updateProfile(request);

            verify(identityProvider).updateUser(any(), any());
            verify(userPreferencesRepository).save(any(UserPreferences.class));
            assertThat(result.companyCountryId()).isEqualTo(countryId);
        }

        @Test
        @DisplayName("should update all location fields correctly")
        void shouldUpdateAllLocationFields() {
            var countryId = UUID.randomUUID();
            var companyId = UUID.randomUUID();
            var regionId = UUID.randomUUID();
            var zoneId = UUID.randomUUID();
            var storeId = UUID.randomUUID();
            var request = new ProfileUpdateRequest(null, null, null,
                    countryId, companyId, regionId, zoneId, storeId);

            var prefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID))
                    .thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class)))
                    .thenReturn(prefs);

            var result = profileService.updateProfile(request);

            assertThat(result.companyCountryId()).isEqualTo(countryId);
            assertThat(result.companyId()).isEqualTo(companyId);
            assertThat(result.companyRegionId()).isEqualTo(regionId);
            assertThat(result.companyZoneId()).isEqualTo(zoneId);
            assertThat(result.companyStoreId()).isEqualTo(storeId);
        }
    }
}
