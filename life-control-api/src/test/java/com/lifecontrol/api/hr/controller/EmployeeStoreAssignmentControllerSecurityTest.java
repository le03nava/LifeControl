package com.lifecontrol.api.hr.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.hr.dto.StoreAssignmentRequest;
import com.lifecontrol.api.hr.dto.StoreAssignmentResponse;
import com.lifecontrol.api.hr.dto.StoreAssignmentResponse.DerivedStoreScope;
import com.lifecontrol.api.hr.service.EmployeeStoreAssignmentService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
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
 * Method-level authorization slice of the store-assignment controller, mirroring
 * {@code ContractControllerSecurityTest}: the read is reachable for any authenticated caller and the
 * create is limited to {@code lc-admin} and {@code lc-employee}. The service is mocked, so the slice
 * boots neither PostgreSQL nor the JWT infrastructure.
 */
@WebMvcTest(EmployeeStoreAssignmentController.class)
@DisplayName("Employee Store Assignment Controller Security — @PreAuthorize method-level authorization")
class EmployeeStoreAssignmentControllerSecurityTest {

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EmployeeStoreAssignmentService employeeStoreAssignmentService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();

    private StoreAssignmentRequest buildRequest() {
        return new StoreAssignmentRequest(UUID.randomUUID(), LocalDate.of(2026, 1, 1));
    }

    private StoreAssignmentResponse buildResponse() {
        return new StoreAssignmentResponse(
                assignmentId,
                UUID.randomUUID(),
                "Store One",
                LocalDate.of(2026, 1, 1),
                null,
                true,
                new DerivedStoreScope(
                        companyId,
                        "Test Company",
                        UUID.randomUUID(),
                        "Mexico",
                        UUID.randomUUID(),
                        "Region",
                        UUID.randomUUID(),
                        "Zone"));
    }

    // ─── Reads: any authenticated user ────────────────────────────

    @Nested
    @DisplayName("GET endpoints")
    class ReadSecurity {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user on the list")
        void anyAuthenticatedUserCanList() throws Exception {
            when(employeeStoreAssignmentService.getAssignments(companyId, employeeId, false))
                    .thenReturn(List.of(buildResponse()));

            mockMvc.perform(get(
                            "/api/companies/{companyId}/employees/{employeeId}/store-assignments",
                            companyId,
                            employeeId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for an unauthenticated list")
        void unauthenticatedListReturns401() throws Exception {
            mockMvc.perform(get(
                            "/api/companies/{companyId}/employees/{employeeId}/store-assignments",
                            companyId,
                            employeeId))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ─── POST ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/companies/{companyId}/employees/{employeeId}/store-assignments")
    class CreateAssignmentSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for lc-admin")
        void adminCanCreate() throws Exception {
            when(employeeStoreAssignmentService.createAssignment(
                            eq(companyId), eq(employeeId), any(StoreAssignmentRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post(
                                    "/api/companies/{companyId}/employees/{employeeId}/store-assignments",
                                    companyId,
                                    employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-employee"})
        @DisplayName("returns 201 Created for lc-employee")
        void employeeRoleCanCreate() throws Exception {
            when(employeeStoreAssignmentService.createAssignment(
                            eq(companyId), eq(employeeId), any(StoreAssignmentRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post(
                                    "/api/companies/{companyId}/employees/{employeeId}/store-assignments",
                                    companyId,
                                    employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-department"})
        @DisplayName("returns 403 Forbidden for an unrelated catalog-write role")
        void unrelatedCatalogRoleGetsForbidden() throws Exception {
            mockMvc.perform(post(
                                    "/api/companies/{companyId}/employees/{employeeId}/store-assignments",
                                    companyId,
                                    employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for a user with the wrong role")
        void wrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(post(
                                    "/api/companies/{companyId}/employees/{employeeId}/store-assignments",
                                    companyId,
                                    employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for an unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post(
                                    "/api/companies/{companyId}/employees/{employeeId}/store-assignments",
                                    companyId,
                                    employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }
    }
}
