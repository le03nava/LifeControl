package com.lifecontrol.api.hr.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.hr.dto.ScopeAssignmentRequest;
import com.lifecontrol.api.hr.dto.ScopeAssignmentResponse;
import com.lifecontrol.api.hr.dto.ScopeAssignmentResponse.DerivedScopeChain;
import com.lifecontrol.api.hr.service.EmployeeScopeAssignmentService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Method-level authorization slice of the scope-assignment controller, mirroring
 * {@code EmployeeStoreAssignmentControllerSecurityTest}: the read is reachable for any authenticated
 * caller and the create and close are limited to {@code lc-admin} and {@code lc-employee} — the exact
 * pair {@code EmployeeStoreAssignmentController} carries, because the same two roles assign per-level
 * scope (S4). The service is mocked, so the slice boots neither PostgreSQL nor the JWT
 * infrastructure.
 *
 * <p>The role literals are written out here on purpose instead of referencing {@code Roles}: this
 * suite is the independent statement of <b>which</b> role names are accepted, so a constant that
 * changed value would fail here instead of silently moving the gate.</p>
 */
@WebMvcTest(EmployeeScopeAssignmentController.class)
@Import(EmployeeScopeAssignmentControllerSecurityTest.TestSecurityConfig.class)
@DisplayName("Employee Scope Assignment Controller Security — @PreAuthorize method-level authorization")
class EmployeeScopeAssignmentControllerSecurityTest {

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
    private EmployeeScopeAssignmentService employeeScopeAssignmentService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();

    private ScopeAssignmentRequest buildRequest() {
        return new ScopeAssignmentRequest(null, UUID.randomUUID(), null, LocalDate.of(2026, 1, 1));
    }

    private ScopeAssignmentResponse buildResponse() {
        return new ScopeAssignmentResponse(
                assignmentId,
                ScopeLevel.REGION,
                null,
                UUID.randomUUID(),
                null,
                "Region",
                LocalDate.of(2026, 1, 1),
                null,
                true,
                new DerivedScopeChain(
                        companyId,
                        "Test Company",
                        UUID.randomUUID(),
                        "Mexico",
                        UUID.randomUUID(),
                        "Region",
                        null,
                        null));
    }

    // ─── Reads: any authenticated user ────────────────────────────

    @Nested
    @DisplayName("GET endpoints")
    class ReadSecurity {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user on the list")
        void anyAuthenticatedUserCanList() throws Exception {
            when(employeeScopeAssignmentService.getAssignments(companyId, employeeId, false))
                    .thenReturn(List.of(buildResponse()));

            mockMvc.perform(get(
                            "/api/companies/{companyId}/employees/{employeeId}/scope-assignments",
                            companyId,
                            employeeId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for an unauthenticated list")
        void unauthenticatedListReturns401() throws Exception {
            mockMvc.perform(get(
                            "/api/companies/{companyId}/employees/{employeeId}/scope-assignments",
                            companyId,
                            employeeId))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ─── POST ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/companies/{companyId}/employees/{employeeId}/scope-assignments")
    class CreateAssignmentSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for lc-admin")
        void adminCanCreate() throws Exception {
            when(employeeScopeAssignmentService.createAssignment(
                            eq(companyId), eq(employeeId), any(ScopeAssignmentRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post(
                                    "/api/companies/{companyId}/employees/{employeeId}/scope-assignments",
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
            when(employeeScopeAssignmentService.createAssignment(
                            eq(companyId), eq(employeeId), any(ScopeAssignmentRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post(
                                    "/api/companies/{companyId}/employees/{employeeId}/scope-assignments",
                                    companyId,
                                    employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 403 Forbidden for the scoped role the assignment itself grants")
        void scopedRoleGetsForbidden() throws Exception {
            mockMvc.perform(post(
                                    "/api/companies/{companyId}/employees/{employeeId}/scope-assignments",
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
                                    "/api/companies/{companyId}/employees/{employeeId}/scope-assignments",
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
                                    "/api/companies/{companyId}/employees/{employeeId}/scope-assignments",
                                    companyId,
                                    employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ─── PATCH …/{id}/close ───────────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/companies/{companyId}/employees/{employeeId}/scope-assignments/{id}/close")
    class CloseAssignmentSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void adminCanClose() throws Exception {
            when(employeeScopeAssignmentService.closeAssignment(eq(companyId), eq(employeeId), eq(assignmentId), any()))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch(
                            "/api/companies/{companyId}/employees/{employeeId}/scope-assignments/{id}/close",
                            companyId,
                            employeeId,
                            assignmentId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-employee"})
        @DisplayName("returns 200 OK for lc-employee")
        void employeeRoleCanClose() throws Exception {
            when(employeeScopeAssignmentService.closeAssignment(eq(companyId), eq(employeeId), eq(assignmentId), any()))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch(
                            "/api/companies/{companyId}/employees/{employeeId}/scope-assignments/{id}/close",
                            companyId,
                            employeeId,
                            assignmentId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 403 Forbidden for a caller holding neither allowed role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch(
                            "/api/companies/{companyId}/employees/{employeeId}/scope-assignments/{id}/close",
                            companyId,
                            employeeId,
                            assignmentId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for an unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(patch(
                            "/api/companies/{companyId}/employees/{employeeId}/scope-assignments/{id}/close",
                            companyId,
                            employeeId,
                            assignmentId))
                    .andExpect(status().isUnauthorized());
        }
    }
}
