package com.lifecontrol.api.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.exception.ResourceNotFoundException;
import com.lifecontrol.api.profile.dto.ProfileResponse;
import com.lifecontrol.api.profile.dto.ProfileUpdateRequest;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConnectionException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileController Tests")
class ProfileControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ProfileService profileService;

    @InjectMocks
    private ProfileController profileController;

    private static final String USER_ID = "kc-user-123";
    private static final String USERNAME = "jdoe";
    private static final String EMAIL = "jdoe@example.com";
    private static final String FIRST_NAME = "John";
    private static final String LAST_NAME = "Doe";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(profileController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(appStrictJsonConverter())
                .build();
    }

    /**
     * The JSON converter with the app's own request-body strictness, so this suite asserts what the
     * running application does instead of what a bare {@code standaloneSetup} happens to do.
     *
     * <p>{@code application.properties} sets
     * {@code spring.jackson.deserialization.fail-on-unknown-properties=true}, which makes an undeclared
     * key in a request body a 400. Boot 4 leaves Jackson 3's own default for that flag at {@code false},
     * so the converter {@code standaloneSetup} installs by default is <b>lenient</b> — a pin written
     * against it passes for the wrong reason and states the opposite of the deployed contract.</p>
     */
    private static JacksonJsonHttpMessageConverter appStrictJsonConverter() {
        var mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        return new JacksonJsonHttpMessageConverter(mapper);
    }

    @Nested
    @DisplayName("GET /api/profile")
    class GetProfileTests {

        @Test
        @DisplayName("should return 200 with full profile")
        void shouldReturnProfile() throws Exception {
            var response = new ProfileResponse(USER_ID, USERNAME, EMAIL, FIRST_NAME, LAST_NAME, null, null, null);

            when(profileService.getProfile()).thenReturn(response);

            mockMvc.perform(get("/api/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.keycloakUserId").value(USER_ID))
                    .andExpect(jsonPath("$.username").value(USERNAME))
                    .andExpect(jsonPath("$.email").value(EMAIL))
                    .andExpect(jsonPath("$.firstName").value(FIRST_NAME))
                    .andExpect(jsonPath("$.lastName").value(LAST_NAME));
            // T14: the four tenancy ancestors are not part of this payload; their absence is pinned by
            // `shouldNotSerializeTheFourTenancyAncestors` so this test stays the plain happy path.
        }

        @Test
        @DisplayName("should not serialize the four tenancy ancestors (W3/T14)")
        void shouldNotSerializeTheFourTenancyAncestors() throws Exception {
            var response = new ProfileResponse(USER_ID, USERNAME, EMAIL, FIRST_NAME, LAST_NAME, null, null, null);

            when(profileService.getProfile()).thenReturn(response);

            mockMvc.perform(get("/api/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.companyId").doesNotExist())
                    .andExpect(jsonPath("$.companyCountryId").doesNotExist())
                    .andExpect(jsonPath("$.companyRegionId").doesNotExist())
                    .andExpect(jsonPath("$.companyZoneId").doesNotExist())
                    .andExpect(jsonPath("$.companyStoreId").value(nullValue()));
        }

        @Test
        @DisplayName("should serialize assignedStores as null for an unconstrained caller (D11)")
        void shouldSerializeNullAssignedStores() throws Exception {
            var response = new ProfileResponse(USER_ID, USERNAME, EMAIL, FIRST_NAME, LAST_NAME, null, null, null);

            when(profileService.getProfile()).thenReturn(response);

            mockMvc.perform(get("/api/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assignedStores").value(nullValue()));
        }

        @Test
        @DisplayName("should serialize each assigned store with its derived chain (T25)")
        void shouldSerializeAssignedStores() throws Exception {
            var storeId = UUID.randomUUID();
            var companyId = UUID.randomUUID();
            var countryId = UUID.randomUUID();
            var regionId = UUID.randomUUID();
            var zoneId = UUID.randomUUID();
            var assignedStore = new ProfileResponse.AssignedStore(
                    storeId, "Main Store", companyId, "Acme", countryId, "México", regionId, "North", zoneId, "Zone 1");
            var response = new ProfileResponse(
                    USER_ID, USERNAME, EMAIL, FIRST_NAME, LAST_NAME, null, List.of(assignedStore), null);

            when(profileService.getProfile()).thenReturn(response);

            mockMvc.perform(get("/api/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assignedStores[0].companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.assignedStores[0].companyStoreName").value("Main Store"))
                    .andExpect(jsonPath("$.assignedStores[0].companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$.assignedStores[0].companyName").value("Acme"))
                    .andExpect(jsonPath("$.assignedStores[0].companyCountryId").value(countryId.toString()))
                    .andExpect(
                            jsonPath("$.assignedStores[0].companyCountryName").value("México"))
                    .andExpect(jsonPath("$.assignedStores[0].companyRegionId").value(regionId.toString()))
                    .andExpect(jsonPath("$.assignedStores[0].companyRegionName").value("North"))
                    .andExpect(jsonPath("$.assignedStores[0].companyZoneId").value(zoneId.toString()))
                    .andExpect(jsonPath("$.assignedStores[0].companyZoneName").value("Zone 1"));
        }

        @Test
        @DisplayName("should serialize an empty assignedStores as [] (constrained to none, D11)")
        void shouldSerializeEmptyAssignedStores() throws Exception {
            var response = new ProfileResponse(USER_ID, USERNAME, EMAIL, FIRST_NAME, LAST_NAME, null, List.of(), null);

            when(profileService.getProfile()).thenReturn(response);

            mockMvc.perform(get("/api/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assignedStores").isArray())
                    .andExpect(jsonPath("$.assignedStores").isEmpty())
                    .andExpect(jsonPath("$.companyStoreId").value(nullValue()));
        }

        @Test
        @DisplayName("should serialize location with its six fields and no ancestor names (S1)")
        void shouldSerializeLocation() throws Exception {
            var storeId = UUID.randomUUID();
            var companyId = UUID.randomUUID();
            var countryId = UUID.randomUUID();
            var regionId = UUID.randomUUID();
            var zoneId = UUID.randomUUID();
            var location =
                    new ProfileResponse.LocationChain(companyId, countryId, regionId, zoneId, storeId, "Main Store");
            var response =
                    new ProfileResponse(USER_ID, USERNAME, EMAIL, FIRST_NAME, LAST_NAME, storeId, List.of(), location);

            when(profileService.getProfile()).thenReturn(response);

            mockMvc.perform(get("/api/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.location.companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$.location.companyCountryId").value(countryId.toString()))
                    .andExpect(jsonPath("$.location.companyRegionId").value(regionId.toString()))
                    .andExpect(jsonPath("$.location.companyZoneId").value(zoneId.toString()))
                    .andExpect(jsonPath("$.location.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.location.companyStoreName").value("Main Store"))
                    // S1: the response carries no ancestor names, so a screen that renders one loads the
                    // catalogue it needs instead of trusting a string from here.
                    .andExpect(jsonPath("$.location.companyName").doesNotExist())
                    .andExpect(jsonPath("$.location.companyCountryName").doesNotExist())
                    .andExpect(jsonPath("$.location.companyRegionName").doesNotExist())
                    .andExpect(jsonPath("$.location.companyZoneName").doesNotExist());
        }

        @Test
        @DisplayName("should serialize location as null when nothing resolves (S2)")
        void shouldSerializeNullLocation() throws Exception {
            var response = new ProfileResponse(USER_ID, USERNAME, EMAIL, FIRST_NAME, LAST_NAME, null, List.of(), null);

            when(profileService.getProfile()).thenReturn(response);

            mockMvc.perform(get("/api/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.location").value(nullValue()));
        }

        @Test
        @DisplayName("a store whose location cannot be derived still answers 200, with location null (S2)")
        void shouldStillAnswerWhenLocationCannotBeDerived() throws Exception {
            var storeId = UUID.randomUUID();
            var response =
                    new ProfileResponse(USER_ID, USERNAME, EMAIL, FIRST_NAME, LAST_NAME, storeId, List.of(), null);

            when(profileService.getProfile()).thenReturn(response);

            // The regression this pins: an unreadable store degrades to no preselection, not to a
            // failed read and not to a different companyStoreId.
            mockMvc.perform(get("/api/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.location").value(nullValue()));
        }

        @Test
        @DisplayName("should return 503 when Keycloak is unreachable")
        void shouldReturn503WhenKeycloakUnreachable() throws Exception {
            when(profileService.getProfile())
                    .thenThrow(new IdentityProviderConnectionException("Keycloak unavailable", new RuntimeException()));

            mockMvc.perform(get("/api/profile")).andExpect(status().isServiceUnavailable());
        }
    }

    @Nested
    @DisplayName("PUT /api/profile")
    class UpdateProfileTests {

        @Test
        @DisplayName("should return 200 with updated profile")
        void shouldUpdateProfile() throws Exception {
            var response = new ProfileResponse(USER_ID, USERNAME, EMAIL, FIRST_NAME, LAST_NAME, null, null, null);

            when(profileService.updateProfile(any(ProfileUpdateRequest.class))).thenReturn(response);

            var body = """
                    {
                        "firstName": "John",
                        "lastName": "Doe",
                        "email": "jdoe@example.com"
                    }
                    """;

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.firstName").value(FIRST_NAME))
                    // T14: the response reports the store preference and no tenancy ancestor; the
                    // absence is pinned by `shouldNotEchoTheFourTenancyAncestors`.
                    .andExpect(jsonPath("$.companyStoreId").value(nullValue()));
        }

        @Test
        @DisplayName(
                "should not echo the four tenancy ancestors, and rejects a body that still carries them with 400 (W3/T14)")
        void shouldNotEchoTheFourTenancyAncestors() throws Exception {
            // The four are unknown request properties after T14. `G13` of company-scope-local-fallback
            // declared them as `ignored`, which the deployed configuration does not do: with
            // `fail-on-unknown-properties=true` the body is refused before the controller runs, so the
            // pin has to assert the refusal — a 200 here passed only because `standaloneSetup`'s default
            // converter is lenient, which is the false green the converter above removes.
            var body = """
                    {
                        "firstName": "John",
                        "companyId": "%s",
                        "companyCountryId": "%s",
                        "companyRegionId": "%s",
                        "companyZoneId": "%s"
                    }
                    """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));

            verify(profileService, never()).updateProfile(any(ProfileUpdateRequest.class));
        }

        @Test
        @DisplayName("should return 400 when email is invalid")
        void shouldReturn400ForInvalidEmail() throws Exception {
            var body = """
                    {
                        "email": "not-an-email"
                    }
                    """;

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 503 when Keycloak fails during update")
        void shouldReturn503WhenKeycloakFails() throws Exception {
            when(profileService.updateProfile(any(ProfileUpdateRequest.class)))
                    .thenThrow(new IdentityProviderConnectionException("Keycloak unavailable", new RuntimeException()));

            var body = """
                    {
                        "firstName": "John"
                    }
                    """;

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isServiceUnavailable());
        }

        @Test
        @DisplayName("should return 404 when user not found in Keycloak")
        void shouldReturn404WhenUserNotFound() throws Exception {
            when(profileService.updateProfile(any(ProfileUpdateRequest.class)))
                    .thenThrow(new IdentityProviderNotFoundException("User not found: unknown"));

            var body = """
                    {
                        "firstName": "John"
                    }
                    """;

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 404 when the store is refused by the assignment constraint (T23)")
        void shouldReturn404WhenStoreIsRefused() throws Exception {
            var storeId = UUID.randomUUID();
            when(profileService.updateProfile(any(ProfileUpdateRequest.class)))
                    .thenThrow(new ResourceNotFoundException(
                            "No current store assignment for the authenticated employee covers store " + storeId));

            var body = """
                    {
                        "companyStoreId": "%s"
                    }
                    """.formatted(storeId.toString());

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message")
                            .value("No current store assignment for the authenticated employee covers store "
                                    + storeId));
        }

        @Test
        @DisplayName("should accept empty object body (no-op update)")
        void shouldAcceptEmptyBody() throws Exception {
            var response = new ProfileResponse(USER_ID, USERNAME, EMAIL, FIRST_NAME, LAST_NAME, null, null, null);

            when(profileService.updateProfile(any(ProfileUpdateRequest.class))).thenReturn(response);

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should serialize the location of the update response too (both call sites)")
        void shouldSerializeLocationOnUpdate() throws Exception {
            var storeId = UUID.randomUUID();
            var location = new ProfileResponse.LocationChain(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), storeId, "Main Store");
            var response =
                    new ProfileResponse(USER_ID, USERNAME, EMAIL, FIRST_NAME, LAST_NAME, storeId, null, location);

            when(profileService.updateProfile(any(ProfileUpdateRequest.class))).thenReturn(response);

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"companyStoreId\": \"%s\"}".formatted(storeId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.location.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.location.companyStoreName").value("Main Store"));
        }

        @Test
        @DisplayName("should deserialize clearStorePreference=true (S4)")
        void shouldAcceptClearStorePreference() throws Exception {
            var response = new ProfileResponse(USER_ID, USERNAME, EMAIL, FIRST_NAME, LAST_NAME, null, null, null);
            when(profileService.updateProfile(any(ProfileUpdateRequest.class))).thenReturn(response);

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"clearStorePreference\": true}"))
                    .andExpect(status().isOk());

            var captor = ArgumentCaptor.forClass(ProfileUpdateRequest.class);
            verify(profileService).updateProfile(captor.capture());
            assertThat(captor.getValue().clearStorePreference()).isTrue();
            assertThat(captor.getValue().companyStoreId()).isNull();
        }

        @Test
        @DisplayName("should not mean 'clear' when the key is absent (S4)")
        void shouldDefaultClearStorePreferenceToFalse() throws Exception {
            var response = new ProfileResponse(USER_ID, USERNAME, EMAIL, FIRST_NAME, LAST_NAME, null, null, null);
            when(profileService.updateProfile(any(ProfileUpdateRequest.class))).thenReturn(response);

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"firstName\": \"John\"}"))
                    .andExpect(status().isOk());

            // S4's default, measured rather than assumed: an omitted key must keep meaning "leave the
            // stored value untouched". The field is a Boolean because Jackson 3 fails an absent
            // primitive creator property outright (FAIL_ON_NULL_FOR_PRIMITIVES is on by default), so a
            // primitive here would 400 every client that does not send the key at all.
            var captor = ArgumentCaptor.forClass(ProfileUpdateRequest.class);
            verify(profileService).updateProfile(captor.capture());
            assertThat(captor.getValue().clearStorePreference()).isNotEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("should reject a location-shaped object in the request body with 400, writing nothing (S3)")
        void shouldRejectALocationShapedObject() throws Exception {
            // location is read-only (S3), and the guarantee is stronger than "ignored": there is no field
            // of ProfileUpdateRequest to feed it, and the undeclared key is refused with 400 by
            // `spring.jackson.deserialization.fail-on-unknown-properties=true`, so the object cannot be
            // sent at all rather than being sent and dropped.
            var body = """
                    {
                        "firstName": "John",
                        "location": {
                            "companyId": "%s",
                            "companyCountryId": "%s",
                            "companyRegionId": "%s",
                            "companyZoneId": "%s",
                            "companyStoreId": "%s",
                            "companyStoreName": "Main Store"
                        }
                    }
                    """.formatted(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));
        }

        @Test
        @DisplayName("should write nothing when a location-shaped object is refused (S3)")
        void shouldWriteNothingForALocationShapedObject() throws Exception {
            var body = """
                    {
                        "firstName": "John",
                        "location": {
                            "companyStoreId": "%s",
                            "companyStoreName": "Main Store"
                        }
                    }
                    """.formatted(UUID.randomUUID());

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            // The 400 alone does not prove the other half of S3. `ProfileService.updateProfile` is the
            // only writer of both Keycloak and `user_preferences`, so "never reached" is exactly "no
            // identity and no preference changed" — the rename of the field did not leave a second path.
            verify(profileService, never()).updateProfile(any(ProfileUpdateRequest.class));
        }

        @Test
        @DisplayName("should return 400 when clearStorePreference is combined with companyStoreId (S5)")
        void shouldReturn400WhenClearAndStoreAreCombined() throws Exception {
            when(profileService.updateProfile(any(ProfileUpdateRequest.class)))
                    .thenThrow(new IllegalArgumentException(
                            "clearStorePreference cannot be combined with companyStoreId"));

            var body = """
                    {
                        "clearStorePreference": true,
                        "companyStoreId": "%s"
                    }
                    """.formatted(UUID.randomUUID());

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.message").value("clearStorePreference cannot be combined with companyStoreId"));
        }
    }
}
