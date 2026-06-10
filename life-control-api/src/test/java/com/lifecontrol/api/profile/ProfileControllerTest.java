package com.lifecontrol.api.profile;

import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.profile.dto.ProfileResponse;
import com.lifecontrol.api.profile.dto.ProfileUpdateRequest;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConnectionException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileController Tests")
class ProfileControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

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
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
    }

    @Nested
    @DisplayName("GET /api/profile")
    class GetProfileTests {

        @Test
        @DisplayName("should return 200 with full profile")
        void shouldReturnProfile() throws Exception {
            var countryId = UUID.randomUUID();
            var response = new ProfileResponse(USER_ID, USERNAME, EMAIL,
                    FIRST_NAME, LAST_NAME, countryId, null, null, null, null);

            when(profileService.getProfile()).thenReturn(response);

            mockMvc.perform(get("/api/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.keycloakUserId").value(USER_ID))
                    .andExpect(jsonPath("$.username").value(USERNAME))
                    .andExpect(jsonPath("$.email").value(EMAIL))
                    .andExpect(jsonPath("$.firstName").value(FIRST_NAME))
                    .andExpect(jsonPath("$.lastName").value(LAST_NAME))
                    .andExpect(jsonPath("$.companyCountryId").value(countryId.toString()));
        }

        @Test
        @DisplayName("should return 503 when Keycloak is unreachable")
        void shouldReturn503WhenKeycloakUnreachable() throws Exception {
            when(profileService.getProfile())
                    .thenThrow(new IdentityProviderConnectionException("Keycloak unavailable", new RuntimeException()));

            mockMvc.perform(get("/api/profile"))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    @Nested
    @DisplayName("PUT /api/profile")
    class UpdateProfileTests {

        @Test
        @DisplayName("should return 200 with updated profile")
        void shouldUpdateProfile() throws Exception {
            var countryId = UUID.randomUUID();
            var response = new ProfileResponse(USER_ID, USERNAME, EMAIL,
                    FIRST_NAME, LAST_NAME, countryId, null, null, null, null);

            when(profileService.updateProfile(any(ProfileUpdateRequest.class)))
                    .thenReturn(response);

            var body = """
                    {
                        "firstName": "John",
                        "lastName": "Doe",
                        "email": "jdoe@example.com",
                        "companyCountryId": "%s"
                    }
                    """.formatted(countryId.toString());

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.firstName").value(FIRST_NAME))
                    .andExpect(jsonPath("$.companyCountryId").value(countryId.toString()));
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
        @DisplayName("should accept empty object body (no-op update)")
        void shouldAcceptEmptyBody() throws Exception {
            var response = new ProfileResponse(USER_ID, USERNAME, EMAIL,
                    FIRST_NAME, LAST_NAME, null, null, null, null, null);

            when(profileService.updateProfile(any(ProfileUpdateRequest.class)))
                    .thenReturn(response);

            mockMvc.perform(put("/api/profile")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }
    }
}
