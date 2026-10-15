package com.lifecontrol.api.provisioning.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.provisioning.service.AccessProvisioningQueryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Pins the {@code @PreAuthorize} role set of the company decision queue: it is the only evidence that
 * the gate exists at all (unit W5d, slice B).
 *
 * <p>The queue is a <b>new authorization surface</b> — a company-scoped read that no earlier unit
 * exposed — so it gets the same coverage the sibling controller's security test gives its own routes:
 * an unauthenticated request is refused with 401, a user with no relevant role with 403, and each of
 * {@code lc-admin} and {@code lc-employee-access} alone is enough. There is deliberately no read-only
 * pair (record O5), for the same reason the employee-scoped read has none: the payload carries role
 * names and the requester's identity, never PII the caller could not already read.</p>
 *
 * <p>The two authorized cases also assert the service was reached, so a 200 is evidence the request got
 * through the gate into the controller and not merely that a role matched.</p>
 */
@WebMvcTest(AccessProvisioningQueueController.class)
@Import({GlobalExceptionHandler.class, AccessProvisioningQueueControllerSecurityTest.TestSecurityConfig.class})
@DisplayName("AccessProvisioningQueueController Security — @PreAuthorize method-level authorization")
class AccessProvisioningQueueControllerSecurityTest {

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

    private static final String BASE_URL = "/api/companies/{companyId}/access/requests";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessProvisioningQueryService accessProvisioningQueryService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(accessProvisioningQueryService.getDecisionQueue(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("returns 401 for an unauthenticated request")
    void unauthenticatedGetsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE_URL, companyId)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "lc-employee")
    @DisplayName("returns 403 for a caller without lc-admin and without lc-employee-access")
    void unrelatedRoleIsForbidden() throws Exception {
        mockMvc.perform(get(BASE_URL, companyId)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "lc-employee-access")
    @DisplayName("returns 200 for lc-employee-access alone and reaches the service")
    void employeeAccessRoleIsEnough() throws Exception {
        mockMvc.perform(get(BASE_URL, companyId)).andExpect(status().isOk());

        verify(accessProvisioningQueryService).getDecisionQueue(companyId);
    }

    @Test
    @WithMockUser(roles = "lc-admin")
    @DisplayName("returns 200 for lc-admin alone and reaches the service")
    void adminRoleIsEnough() throws Exception {
        mockMvc.perform(get(BASE_URL, companyId)).andExpect(status().isOk());

        verify(accessProvisioningQueryService).getDecisionQueue(companyId);
    }
}
