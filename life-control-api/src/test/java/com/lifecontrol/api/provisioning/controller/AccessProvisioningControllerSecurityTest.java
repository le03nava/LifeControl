package com.lifecontrol.api.provisioning.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningClaims;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningOverview;
import com.lifecontrol.api.provisioning.dto.AccessProvisioningRoleDiff;
import com.lifecontrol.api.provisioning.service.AccessProvisioningQueryService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the {@code @PreAuthorize} role set of {@code GET …/access}: it is the only evidence that the
 * gate exists at all. A user with no relevant role is refused with 403, and each of {@code lc-admin}
 * and {@code lc-employee-access} alone is enough to read — there is no read-only pair
 * (record {@code employee-access-provisioning} O5).
 */
@WebMvcTest(AccessProvisioningController.class)
@Import({GlobalExceptionHandler.class, AccessProvisioningControllerSecurityTest.TestSecurityConfig.class})
@DisplayName("AccessProvisioningController Security — @PreAuthorize method-level authorization")
class AccessProvisioningControllerSecurityTest {

    /**
     * Minimal security configuration that enables method-level security without requiring JWT/OAuth2
     * infrastructure. {@code @WithMockUser} sets up the SecurityContext directly, bypassing
     * authentication filters.
     */
    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(basic -> {})
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .build();
        }
    }

    private static final String BASE_URL = "/api/companies/{companyId}/employees/{employeeId}/access";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessProvisioningQueryService accessProvisioningQueryService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(accessProvisioningQueryService.getAccessOverview(any(), any())).thenReturn(emptyOverview());
    }

    private AccessProvisioningOverview emptyOverview() {
        return new AccessProvisioningOverview(
                null,
                false,
                Set.of(),
                Set.of(),
                new AccessProvisioningRoleDiff(Set.of(), Set.of()),
                null,
                List.of(),
                AccessProvisioningClaims.empty());
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAccessOverview {

        @Test
        @DisplayName("returns 401 for an unauthenticated request")
        void unauthenticatedGetsUnauthorized() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, employeeId)).andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for a user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-employee"})
        @DisplayName("returns 403 for the HR employee role alone, which is a different power")
        void employeeRoleAloneIsNotEnough() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, employeeId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 for lc-admin alone")
        void adminCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, employeeId)).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-employee-access"})
        @DisplayName("returns 200 for lc-employee-access alone")
        void employeeAccessRoleCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, employeeId)).andExpect(status().isOk());
        }
    }
}
