package com.lifecontrol.api.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.exception.ResourceNotFoundException;
import com.lifecontrol.api.hr.dto.StoreAssignmentResponse;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.service.EmployeeStoreAssignmentService;
import com.lifecontrol.api.profile.dto.ProfileUpdateRequest;
import com.lifecontrol.api.store.dto.CompanyStoreResponse;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.service.CompanyStoreService;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.model.UserPreferences;
import com.lifecontrol.api.usersadmin.repository.UserPreferencesRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

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
    private EmployeeRepository employeeRepository;

    @Mock
    private EmployeeStoreAssignmentService employeeStoreAssignmentService;

    @Mock
    private CompanyStoreService companyStoreService;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    @Mock
    private Jwt jwt;

    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(
                currentUserContext,
                identityProvider,
                userPreferencesRepository,
                employeeRepository,
                employeeStoreAssignmentService,
                new ProfileLocationResolver(companyStoreService));

        when(currentUserContext.getUserId()).thenReturn(USER_ID);
        when(currentUserContext.getUsername()).thenReturn(USERNAME);

        // Every pre-existing case is not about the store constraint, so the caller has no employee
        // row (D11): unconstrained, which is exactly today's behaviour. Cases that are about the
        // constraint re-stub this with an employee row.
        lenient().when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.empty());

        // Stub JWT extraction for claims. Lenient because a refusal under T11/T23 short-circuits
        // before Keycloak is touched and therefore never reaches the claim extraction.
        SecurityContextHolder.setContext(securityContext);
        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        lenient().when(authentication.getPrincipal()).thenReturn(jwt);
        lenient().when(jwt.getClaimAsString("email")).thenReturn(EMAIL);
        lenient().when(jwt.getClaimAsString("given_name")).thenReturn(FIRST_NAME);
        lenient().when(jwt.getClaimAsString("family_name")).thenReturn(LAST_NAME);

        // Every case below that is not about the derived location may still resolve a store, and the
        // location must never be what breaks it: the authorized store read answers with a synthetic
        // chain unless the case stubs something else. Cases that are about location stub it themselves.
        lenient()
                .when(companyStoreService.getStoreById(any()))
                .thenAnswer(invocation -> storeWithChain(
                        invocation.getArgument(0),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Some Store"));
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
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));

            var result = profileService.getProfile();

            assertThat(result.keycloakUserId()).isEqualTo(USER_ID);
            assertThat(result.username()).isEqualTo(USERNAME);
            assertThat(result.email()).isEqualTo(EMAIL);
            assertThat(result.firstName()).isEqualTo(FIRST_NAME);
            assertThat(result.lastName()).isEqualTo(LAST_NAME);
            // T14/T18: the row keeps its four columns and the response never repeats them — the profile
            // screen stopped writing them, and nothing reads them for authorization.
            assertThat(prefs.getCompanyCountryId()).isEqualTo(countryId);
            assertThat(prefs.getCompanyId()).isEqualTo(companyId);
            assertThat(result.companyStoreId()).isNull();
        }

        @Test
        @DisplayName("should create empty preferences when none exist")
        void shouldCreateEmptyPreferencesWhenNoneExist() {
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.empty());
            var savedPrefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(savedPrefs);

            var result = profileService.getProfile();

            assertThat(result.keycloakUserId()).isEqualTo(USER_ID);
            // T14: the four tenancy ancestors are not on the wire; storage keeps them and the response
            // reports only the store preference.
            assertThat(result.companyStoreId()).isNull();

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
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));

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
            var request = new ProfileUpdateRequest("NewFirst", "NewLast", "new@example.com", null, false);

            var prefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(prefs);

            var result = profileService.updateProfile(request);

            verify(identityProvider).updateUser(any(String.class), any());
            verify(userPreferencesRepository).save(any(UserPreferences.class));
            // T14: the response no longer carries the four tenancy ancestors; the store preference is the
            // only location value it reports.
            assertThat(result.companyStoreId()).isNull();
        }

        @Test
        @DisplayName("should skip Keycloak update when no identity fields provided")
        void shouldSkipKeycloakUpdateWhenNoIdentityFields() {
            var countryId = UUID.randomUUID();
            var request = new ProfileUpdateRequest(null, null, null, null, false);

            var prefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(prefs);

            profileService.updateProfile(request);

            verify(identityProvider, never()).updateUser(any(), any());
            verify(userPreferencesRepository).save(any(UserPreferences.class));
        }

        @Test
        @DisplayName("should create preferences row when none exists on update")
        void shouldCreatePreferencesWhenNoneExists() {
            var request = new ProfileUpdateRequest("First", null, null, null, false);

            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.empty());

            var savedPrefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(savedPrefs);

            var result = profileService.updateProfile(request);

            verify(identityProvider).updateUser(any(), any());
            verify(userPreferencesRepository).save(any(UserPreferences.class));
            // T14/T18: the request cannot carry the four any more, so the row this update creates keeps
            // them null and the response reports only the store preference.
            assertThat(savedPrefs.getCompanyCountryId()).isNull();
            assertThat(savedPrefs.getCompanyId()).isNull();
            assertThat(savedPrefs.getCompanyRegionId()).isNull();
            assertThat(savedPrefs.getCompanyZoneId()).isNull();
            assertThat(result.companyStoreId()).isNull();
        }

        @Test
        @DisplayName("should write only the store and leave the four tenancy ancestors untouched (T14/T18)")
        void shouldWriteOnlyTheStoreAndLeaveTheFourAncestorsUntouched() {
            var countryId = UUID.randomUUID();
            var companyId = UUID.randomUUID();
            var regionId = UUID.randomUUID();
            var zoneId = UUID.randomUUID();
            var storeId = UUID.randomUUID();
            var request = new ProfileUpdateRequest(null, null, null, storeId, false);

            // The row already holds a chain the subject typed while the profile screen still offered it.
            // The update must leave every one of those columns alone: they are not writable any more
            // (T14) and they are not cleaned either (T18).
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyCountryId(countryId)
                    .companyId(companyId)
                    .companyRegionId(regionId)
                    .companyZoneId(zoneId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(prefs);

            var result = profileService.updateProfile(request);

            assertThat(result.companyStoreId()).isEqualTo(storeId);
            assertThat(prefs.getCompanyStoreId()).isEqualTo(storeId);
            assertThat(prefs.getCompanyCountryId()).isEqualTo(countryId);
            assertThat(prefs.getCompanyId()).isEqualTo(companyId);
            assertThat(prefs.getCompanyRegionId()).isEqualTo(regionId);
            assertThat(prefs.getCompanyZoneId()).isEqualTo(zoneId);
        }

        @Test
        @DisplayName("still accepts a request that carries no location at all, and clears nothing (T14 supersedes T22)")
        void updateProfile_noLocationAtAll_leavesTheFourAncestorsUntouched() {
            var countryId = UUID.randomUUID();
            var companyId = UUID.randomUUID();
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyCountryId(countryId)
                    .companyId(companyId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(prefs);

            // T14 supersedes T22: the four are not "free-form but unvalidated", they are not writable at
            // all — a request cannot name one, and an update with no store leaves the row as it was.
            var request = new ProfileUpdateRequest(null, null, null, null, false);
            var result = profileService.updateProfile(request);

            assertThat(result.companyStoreId()).isNull();
            assertThat(prefs.getCompanyCountryId()).isEqualTo(countryId);
            assertThat(prefs.getCompanyId()).isEqualTo(companyId);
        }
    }

    // ── the store preference constrained to the current assignments (T11/D10/T25) ──

    private static final UUID EMPLOYEE_ID = UUID.randomUUID();

    private static Employee employeeRow() {
        return Employee.builder().id(EMPLOYEE_ID).build();
    }

    private static CompanyStoreResponse storeWithChain(
            UUID storeId, UUID companyId, UUID countryId, UUID regionId, UUID zoneId, String storeName) {
        return new CompanyStoreResponse(
                storeId, companyId, countryId, regionId, zoneId, storeName, null, null, null, true, null, null, 0L);
    }

    private static StoreAssignmentResponse assignedStoreResponse(UUID companyStoreId) {
        return new StoreAssignmentResponse(
                UUID.randomUUID(),
                companyStoreId,
                "Assigned Store",
                LocalDate.now().minusDays(30),
                null,
                true,
                new StoreAssignmentResponse.DerivedStoreScope(
                        UUID.randomUUID(),
                        "Assigned Company",
                        UUID.randomUUID(),
                        "Assigned Country",
                        UUID.randomUUID(),
                        "Assigned Region",
                        UUID.randomUUID(),
                        "Assigned Zone"));
    }

    @Nested
    @DisplayName("the write refusal (T11/T23/T24)")
    class StoreRefusalTests {

        @Test
        @DisplayName("accepts a companyStoreId covered by a current assignment")
        void updateProfile_assignedStore_isAccepted() {
            var assignedStoreId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(assignedStoreId)));
            var prefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(prefs);

            var request = new ProfileUpdateRequest(null, null, null, assignedStoreId, false);
            var result = profileService.updateProfile(request);

            assertThat(result.companyStoreId()).isEqualTo(assignedStoreId);
            assertThat(result.assignedStores()).hasSize(1);
            assertThat(result.assignedStores().get(0).companyStoreId()).isEqualTo(assignedStoreId);
        }

        @Test
        @DisplayName("refuses a store no current assignment covers, before Keycloak is touched")
        void updateProfile_storeOutsideTheAssignedSet_isRefused() {
            var requestedStoreId = UUID.randomUUID();
            var otherAssignedStoreId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(otherAssignedStoreId)));

            var request = new ProfileUpdateRequest("NewFirst", null, null, requestedStoreId, false);

            assertThatThrownBy(() -> profileService.updateProfile(request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("No current store assignment for the authenticated employee covers store "
                            + requestedStoreId);

            verify(identityProvider, never()).updateUser(any(), any());
            verify(userPreferencesRepository, never()).save(any(UserPreferences.class));
        }

        @Test
        @DisplayName("an employee with no assignments refuses every store")
        void updateProfile_employeeWithNoAssignments_refusesTheStore() {
            var requestedStoreId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of());

            var request = new ProfileUpdateRequest(null, null, null, requestedStoreId, false);

            assertThatThrownBy(() -> profileService.updateProfile(request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("No current store assignment for the authenticated employee covers store "
                            + requestedStoreId);
        }

        @Test
        @DisplayName("accepts a null store alongside a stored chain, and reports no chain (T14 supersedes T22)")
        void updateProfile_cascadeFieldsAreNotConstrained() {
            var assignedStoreId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(assignedStoreId)));
            var prefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(prefs);

            // T22 said only companyStoreId is constrained while the four stay free-form. T14 removes the
            // premise: there is no request field to be free-form, so what is left to pin is that a
            // request with no store touches none of the four columns and reports no store.
            var request = new ProfileUpdateRequest(null, null, null, null, false);
            var result = profileService.updateProfile(request);

            assertThat(result.companyStoreId()).isNull();
            assertThat(prefs.getCompanyCountryId()).isNull();
            assertThat(prefs.getCompanyId()).isNull();
            assertThat(prefs.getCompanyRegionId()).isNull();
            assertThat(prefs.getCompanyZoneId()).isNull();
        }

        @Test
        @DisplayName(
                "accepts an assigned store and writes only that store, leaving the ancestors alone (T14 supersedes T22)")
        void updateProfile_assignedStoreAndArbitraryAncestors_areStoredVerbatim() {
            var assignedStoreId = UUID.randomUUID();
            var countryId = UUID.randomUUID();
            var companyId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(assignedStoreId)));
            // A chain the subject typed while the profile screen still offered it: the update writes the
            // store and leaves every one of these columns untouched (T14), and cleans none of them (T18).
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyCountryId(countryId)
                    .companyId(companyId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(prefs);

            var request = new ProfileUpdateRequest(null, null, null, assignedStoreId, false);
            var result = profileService.updateProfile(request);

            assertThat(result.companyStoreId()).isEqualTo(assignedStoreId);
            assertThat(prefs.getCompanyStoreId()).isEqualTo(assignedStoreId);
            assertThat(prefs.getCompanyCountryId()).isEqualTo(countryId);
            assertThat(prefs.getCompanyId()).isEqualTo(companyId);
        }

        @Test
        @DisplayName("accepts any companyStoreId when the caller has no employee row (D11)")
        void updateProfile_noEmployeeRow_acceptsAnyStore() {
            var storeId = UUID.randomUUID();
            var prefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(prefs);

            var request = new ProfileUpdateRequest(null, null, null, storeId, false);
            var result = profileService.updateProfile(request);

            assertThat(result.companyStoreId()).isEqualTo(storeId);
            assertThat(result.assignedStores()).isNull();
            verify(employeeStoreAssignmentService, never()).getCurrentAssignmentsForEmployee(any());
        }
    }

    @Nested
    @DisplayName("the read-through of D10 and the assignedStores discriminator (T25)")
    class ReadThroughTests {

        @Test
        @DisplayName("GET resolves a stored store that is no longer assigned to null, with the assigned set")
        void getProfile_storedStoreNoLongerAssigned_returnsNullAndTheAssignedSet() {
            var staleStoreId = UUID.randomUUID();
            var assignedStoreId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(assignedStoreId)));
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(staleStoreId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));

            var result = profileService.getProfile();

            assertThat(result.companyStoreId()).isNull();
            assertThat(result.assignedStores()).hasSize(1);
            assertThat(result.assignedStores().get(0).companyStoreId()).isEqualTo(assignedStoreId);
        }

        @Test
        @DisplayName("GET returns the stored store while a current assignment still covers it")
        void getProfile_storedStoreStillAssigned_returnsTheStoredValue() {
            var assignedStoreId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(assignedStoreId)));
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(assignedStoreId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));

            var result = profileService.getProfile();

            assertThat(result.companyStoreId()).isEqualTo(assignedStoreId);
        }

        @Test
        @DisplayName("GET without an employee row returns the stored value and a null assigned set (D11)")
        void getProfile_noEmployeeRow_returnsStoredValueAndNullAssignedStores() {
            var storeId = UUID.randomUUID();
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(storeId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));

            var result = profileService.getProfile();

            assertThat(result.companyStoreId()).isEqualTo(storeId);
            assertThat(result.assignedStores()).isNull();
            verify(employeeStoreAssignmentService, never()).getCurrentAssignmentsForEmployee(any());
        }

        @Test
        @DisplayName("the PUT response mirrors D10 instead of echoing the stored column")
        void updateProfile_responseMirrorsTheResolvedValue() {
            var staleStoreId = UUID.randomUUID();
            var assignedStoreId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(assignedStoreId)));
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(staleStoreId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(prefs);

            var request = new ProfileUpdateRequest(null, null, null, null, false);
            var result = profileService.updateProfile(request);

            assertThat(result.companyStoreId()).isNull();
            assertThat(result.assignedStores()).hasSize(1);
            assertThat(result.assignedStores().get(0).companyStoreId()).isEqualTo(assignedStoreId);
        }

        @Test
        @DisplayName("each assigned store carries its derived chain (T25/T14)")
        void getProfile_assignedStoresCarryTheirDerivedChain() {
            var storeId = UUID.randomUUID();
            var companyId = UUID.randomUUID();
            var countryId = UUID.randomUUID();
            var regionId = UUID.randomUUID();
            var zoneId = UUID.randomUUID();
            var assignment = new StoreAssignmentResponse(
                    UUID.randomUUID(),
                    storeId,
                    "Main Store",
                    LocalDate.now().minusDays(1),
                    null,
                    true,
                    new StoreAssignmentResponse.DerivedStoreScope(
                            companyId, "Acme", countryId, "México", regionId, "North", zoneId, "Zone 1"));
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignment));
            var prefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));

            var result = profileService.getProfile();

            var assignedStore = result.assignedStores().get(0);
            assertThat(assignedStore.companyStoreId()).isEqualTo(storeId);
            assertThat(assignedStore.companyStoreName()).isEqualTo("Main Store");
            assertThat(assignedStore.companyId()).isEqualTo(companyId);
            assertThat(assignedStore.companyName()).isEqualTo("Acme");
            assertThat(assignedStore.companyCountryId()).isEqualTo(countryId);
            assertThat(assignedStore.companyCountryName()).isEqualTo("México");
            assertThat(assignedStore.companyRegionId()).isEqualTo(regionId);
            assertThat(assignedStore.companyRegionName()).isEqualTo("North");
            assertThat(assignedStore.companyZoneId()).isEqualTo(zoneId);
            assertThat(assignedStore.companyZoneName()).isEqualTo("Zone 1");
        }

        @Test
        @DisplayName("GET with an existing preferences row writes nothing (D10)")
        void getProfile_existingRow_writesNothing() {
            var assignedStoreId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(assignedStoreId)));
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(assignedStoreId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));

            var result = profileService.getProfile();

            assertThat(result.companyStoreId()).isEqualTo(assignedStoreId);
            verify(userPreferencesRepository, never()).save(any(UserPreferences.class));
        }

        @Test
        @DisplayName("GET writes nothing even when the stored store resolves to null (D10)")
        void getProfile_storedStoreResolvedToNull_writesNothing() {
            var staleStoreId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of());
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(staleStoreId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));

            var result = profileService.getProfile();

            assertThat(result.companyStoreId()).isNull();
            // The stale value stays in the column: the read resolves it, it does not repair it.
            assertThat(prefs.getCompanyStoreId()).isEqualTo(staleStoreId);
            verify(userPreferencesRepository, never()).save(any(UserPreferences.class));
        }

        @Test
        @DisplayName("GET for an employee with a row and zero assignments returns an empty list, not null (D11)")
        void getProfile_employeeWithRowAndNoAssignments_returnsEmptyAssignedStores() {
            var staleStoreId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of());
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(staleStoreId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));

            var result = profileService.getProfile();

            // Constrained to exactly none: distinct from D11's null, which means unconstrained, and
            // the stored column does not leak into the resolved value.
            assertThat(result.assignedStores()).isNotNull().isEmpty();
            assertThat(result.companyStoreId()).isNull();
        }
    }

    // ── location: the derived chain of the resolved store (S1/S2/S3) ──

    @Nested
    @DisplayName("location: the derived chain of the resolved store (S1/S2)")
    class LocationChainTests {

        @Test
        @DisplayName("GET carries the six-field chain derived from the resolved store, and no ancestor names (S1)")
        void getProfile_resolvedStore_carriesItsChain() {
            var storeId = UUID.randomUUID();
            var companyId = UUID.randomUUID();
            var countryId = UUID.randomUUID();
            var regionId = UUID.randomUUID();
            var zoneId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(storeId)));
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(storeId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            // The assignment row carries its own chain, deliberately different from this one: the
            // location comes from the store itself, through the same authorized read GET /api/stores/{id}
            // uses, and not from a copy of the assignment (S1).
            when(companyStoreService.getStoreById(storeId))
                    .thenReturn(storeWithChain(storeId, companyId, countryId, regionId, zoneId, "Main Store"));

            var result = profileService.getProfile();

            assertThat(result.companyStoreId()).isEqualTo(storeId);
            assertThat(result.location()).isNotNull();
            assertThat(result.location().companyId()).isEqualTo(companyId);
            assertThat(result.location().companyCountryId()).isEqualTo(countryId);
            assertThat(result.location().companyRegionId()).isEqualTo(regionId);
            assertThat(result.location().companyZoneId()).isEqualTo(zoneId);
            assertThat(result.location().companyStoreId()).isEqualTo(storeId);
            assertThat(result.location().companyStoreName()).isEqualTo("Main Store");
        }

        @Test
        @DisplayName("location is null when there is no store preference (S2)")
        void getProfile_noStorePreference_leavesLocationNull() {
            var assignedStoreId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(assignedStoreId)));
            var prefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));

            var result = profileService.getProfile();

            assertThat(result.companyStoreId()).isNull();
            assertThat(result.location()).isNull();
            // Nothing to derive from: the store read is not even attempted.
            verify(companyStoreService, never()).getStoreById(any());
        }

        @Test
        @DisplayName("location is null when the stored store is not covered by a current assignment (S2/D10)")
        void getProfile_storedStoreNotAssigned_leavesLocationNull() {
            var staleStoreId = UUID.randomUUID();
            var assignedStoreId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(assignedStoreId)));
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(staleStoreId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));

            var result = profileService.getProfile();

            assertThat(result.companyStoreId()).isNull();
            assertThat(result.location()).isNull();
            verify(companyStoreService, never()).getStoreById(any());
        }

        @Test
        @DisplayName("location is null when the store can no longer be read, and the read still answers (S2)")
        void getProfile_storeNoLongerReadable_stillAnswersTheProfile() {
            var storeId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(storeId)));
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(storeId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(companyStoreService.getStoreById(storeId)).thenThrow(new CompanyStoreNotFoundException(storeId));

            var result = profileService.getProfile();

            // This is the regression that matters: a store the caller can no longer read must not
            // turn into a 404, a 500 or an empty profile. The rest of the payload is untouched and
            // companyStoreId keeps exactly the resolution it has today.
            assertThat(result.keycloakUserId()).isEqualTo(USER_ID);
            assertThat(result.username()).isEqualTo(USERNAME);
            assertThat(result.email()).isEqualTo(EMAIL);
            assertThat(result.companyStoreId()).isEqualTo(storeId);
            assertThat(result.assignedStores()).hasSize(1);
            assertThat(result.location()).isNull();
            verify(userPreferencesRepository, never()).save(any(UserPreferences.class));
        }

        @Test
        @DisplayName("location is null when the store read raises AccessDeniedException defensively (S2)")
        void getProfile_storeReadDenied_leavesLocationNull() {
            var storeId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(storeId)));
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(storeId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(companyStoreService.getStoreById(storeId)).thenThrow(new AccessDeniedException("denied"));

            var result = profileService.getProfile();

            assertThat(result.username()).isEqualTo(USERNAME);
            assertThat(result.companyStoreId()).isEqualTo(storeId);
            assertThat(result.location()).isNull();
        }

        @Test
        @DisplayName("the PUT response carries the same chain as the GET (both call sites)")
        void updateProfile_resolvedStore_carriesTheSameChain() {
            var storeId = UUID.randomUUID();
            var companyId = UUID.randomUUID();
            var countryId = UUID.randomUUID();
            var regionId = UUID.randomUUID();
            var zoneId = UUID.randomUUID();
            when(employeeRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(employeeRow()));
            when(employeeStoreAssignmentService.getCurrentAssignmentsForEmployee(EMPLOYEE_ID))
                    .thenReturn(List.of(assignedStoreResponse(storeId)));
            var prefs = UserPreferences.builder().keycloakUserId(USER_ID).build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(prefs);
            when(companyStoreService.getStoreById(storeId))
                    .thenReturn(storeWithChain(storeId, companyId, countryId, regionId, zoneId, "Main Store"));

            var request = new ProfileUpdateRequest(null, null, null, storeId, false);
            var result = profileService.updateProfile(request);

            assertThat(result.companyStoreId()).isEqualTo(storeId);
            assertThat(result.location()).isNotNull();
            assertThat(result.location().companyId()).isEqualTo(companyId);
            assertThat(result.location().companyCountryId()).isEqualTo(countryId);
            assertThat(result.location().companyRegionId()).isEqualTo(regionId);
            assertThat(result.location().companyZoneId()).isEqualTo(zoneId);
            assertThat(result.location().companyStoreId()).isEqualTo(storeId);
            assertThat(result.location().companyStoreName()).isEqualTo("Main Store");
        }
    }

    // ── clearStorePreference: the column could not be cleared at all before (S4/S5) ──

    @Nested
    @DisplayName("clearStorePreference (S4/S5)")
    class ClearStorePreferenceTests {

        @Test
        @DisplayName("true clears the stored company_store_id (S4)")
        void updateProfile_clearStorePreference_clearsTheStoredColumn() {
            var storedStoreId = UUID.randomUUID();
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(storedStoreId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(prefs);

            var request = new ProfileUpdateRequest(null, null, null, null, true);
            var result = profileService.updateProfile(request);

            assertThat(prefs.getCompanyStoreId()).isNull();
            assertThat(result.companyStoreId()).isNull();
            assertThat(result.location()).isNull();
            verify(userPreferencesRepository).save(prefs);
        }

        @Test
        @DisplayName("false with no companyStoreId leaves the stored column untouched (S4, pinned)")
        void updateProfile_noClearAndNoStore_keepsTheStoredColumn() {
            var storedStoreId = UUID.randomUUID();
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(storedStoreId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(prefs);

            var request = new ProfileUpdateRequest(null, null, null, null, false);
            var result = profileService.updateProfile(request);

            assertThat(prefs.getCompanyStoreId()).isEqualTo(storedStoreId);
            assertThat(result.companyStoreId()).isEqualTo(storedStoreId);
        }

        @Test
        @DisplayName("an absent flag (null, the way Jackson delivers an omitted key) clears nothing (S4)")
        void updateProfile_absentFlag_keepsTheStoredColumn() {
            var storedStoreId = UUID.randomUUID();
            var prefs = UserPreferences.builder()
                    .keycloakUserId(USER_ID)
                    .companyStoreId(storedStoreId)
                    .build();
            when(userPreferencesRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(prefs));
            when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(prefs);

            var request = new ProfileUpdateRequest(null, null, null, null, null);
            var result = profileService.updateProfile(request);

            assertThat(prefs.getCompanyStoreId()).isEqualTo(storedStoreId);
            assertThat(result.companyStoreId()).isEqualTo(storedStoreId);
        }

        @Test
        @DisplayName("true together with a companyStoreId is refused, before Keycloak is touched (S5/T24)")
        void updateProfile_clearTogetherWithStore_isRefusedBeforeKeycloak() {
            var request = new ProfileUpdateRequest(FIRST_NAME, LAST_NAME, EMAIL, UUID.randomUUID(), true);

            assertThatThrownBy(() -> profileService.updateProfile(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("clearStorePreference cannot be combined with companyStoreId");

            // T24: the value to preserve is the identity that is not yet changed, and the preference
            // row that is not cleared by a request that was refused.
            verify(identityProvider, never()).updateUser(any(), any());
            verify(userPreferencesRepository, never()).save(any(UserPreferences.class));
        }
    }
}
