package com.lifecontrol.api.hr.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.hr.dto.EmployeeEmailSuggestionResponse;
import com.lifecontrol.api.hr.dto.EmployeeRequest;
import com.lifecontrol.api.hr.dto.EmployeeResponse;
import com.lifecontrol.api.hr.service.EmployeeService;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

@WebMvcTest(EmployeeController.class)
@DisplayName("Employee Controller Security — @PreAuthorize method-level authorization")
class EmployeeControllerSecurityTest {

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
    private EmployeeService employeeService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    private EmployeeRequest buildRequest() {
        return new EmployeeRequest(
                "EMP-001",
                "Juan",
                "Pérez",
                null,
                null,
                null,
                LocalDate.of(1990, 1, 1),
                LocalDate.of(2020, 1, 1),
                null,
                null,
                null);
    }

    private EmployeeResponse buildResponse() {
        return new EmployeeResponse(
                employeeId,
                companyId,
                "EMP-001",
                "Juan",
                "Pérez",
                null,
                "juan.perez@example.com",
                null,
                LocalDate.of(1990, 1, 1),
                LocalDate.of(2020, 1, 1),
                null,
                null,
                UUID.randomUUID(),
                "Active",
                null,
                true,
                0L,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    // ─── Reads: any authenticated user ────────────────────────────

    @Nested
    @DisplayName("GET endpoints")
    class ReadSecurity {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user on the list")
        void anyAuthenticatedUserCanList() throws Exception {
            when(employeeService.getAllEmployees(companyId, null, null, false)).thenReturn(List.of(buildResponse()));

            mockMvc.perform(get("/api/companies/{companyId}/employees", companyId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for an unauthenticated list")
        void unauthenticatedListReturns401() throws Exception {
            mockMvc.perform(get("/api/companies/{companyId}/employees", companyId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user on GET /{id}")
        void anyAuthenticatedUserCanReadById() throws Exception {
            when(employeeService.getEmployeeById(companyId, employeeId)).thenReturn(buildResponse());

            mockMvc.perform(get("/api/companies/{companyId}/employees/{id}", companyId, employeeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user on suggest-email")
        void anyAuthenticatedUserCanSuggest() throws Exception {
            when(employeeService.suggestEmail(companyId, "Juan", "Pérez"))
                    .thenReturn(new EmployeeEmailSuggestionResponse("juan.perez@example.com", null));

            mockMvc.perform(get("/api/companies/{companyId}/employees/suggest-email", companyId)
                            .param("firstName", "Juan")
                            .param("paternalLastName", "Pérez"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for an unauthenticated suggest-email")
        void unauthenticatedSuggestReturns401() throws Exception {
            mockMvc.perform(get("/api/companies/{companyId}/employees/suggest-email", companyId)
                            .param("firstName", "Juan")
                            .param("paternalLastName", "Pérez"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ─── POST ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/companies/{companyId}/employees")
    class CreateEmployeeSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for lc-admin")
        void adminCanCreate() throws Exception {
            when(employeeService.createEmployee(eq(companyId), any(EmployeeRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/companies/{companyId}/employees", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-employee"})
        @DisplayName("returns 201 Created for lc-employee")
        void employeeRoleCanCreate() throws Exception {
            when(employeeService.createEmployee(eq(companyId), any(EmployeeRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/companies/{companyId}/employees", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for an unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/employees", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for a user with the wrong role")
        void wrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/employees", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/companies/{companyId}/employees/{id}")
    class UpdateEmployeeSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void adminCanUpdate() throws Exception {
            when(employeeService.updateEmployee(eq(companyId), eq(employeeId), any(EmployeeRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/companies/{companyId}/employees/{id}", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-employee"})
        @DisplayName("returns 200 OK for lc-employee")
        void employeeRoleCanUpdate() throws Exception {
            when(employeeService.updateEmployee(eq(companyId), eq(employeeId), any(EmployeeRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/companies/{companyId}/employees/{id}", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for a user with the wrong role")
        void wrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/companies/{companyId}/employees/{id}", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE ───────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/companies/{companyId}/employees/{id}")
    class DeleteEmployeeSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for lc-admin")
        void adminCanDelete() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/employees/{id}", companyId, employeeId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-employee"})
        @DisplayName("returns 204 No Content for lc-employee")
        void employeeRoleCanDelete() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/employees/{id}", companyId, employeeId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for an authenticated user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/employees/{id}", companyId, employeeId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for a user with the wrong role")
        void wrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/employees/{id}", companyId, employeeId))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PATCH enable ─────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/companies/{companyId}/employees/{id}/enable")
    class EnableEmployeeSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void adminCanEnable() throws Exception {
            when(employeeService.setEmployeeEnabled(companyId, employeeId, true))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/companies/{companyId}/employees/{id}/enable", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-employee"})
        @DisplayName("returns 200 OK for lc-employee")
        void employeeRoleCanEnable() throws Exception {
            when(employeeService.setEmployeeEnabled(companyId, employeeId, true))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/companies/{companyId}/employees/{id}/enable", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for a user with the wrong role")
        void wrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch("/api/companies/{companyId}/employees/{id}/enable", companyId, employeeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isForbidden());
        }
    }
}
