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
import com.lifecontrol.api.hr.dto.DepartmentRequest;
import com.lifecontrol.api.hr.dto.DepartmentResponse;
import com.lifecontrol.api.hr.service.DepartmentService;
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

@WebMvcTest(DepartmentController.class)
@DisplayName("Department Controller Security — @PreAuthorize method-level authorization")
class DepartmentControllerSecurityTest {

    /**
     * Minimal security configuration that enables method-level security
     * without requiring JWT/OAuth2 infrastructure. @WithMockUser sets up
     * the SecurityContext directly, bypassing authentication filters.
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
    private DepartmentService departmentService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID departmentId = UUID.randomUUID();

    private DepartmentRequest buildRequest() {
        return new DepartmentRequest("OPS", "Operations", "Operations department", 1, true);
    }

    private DepartmentResponse buildResponse() {
        return new DepartmentResponse(
                departmentId,
                companyId,
                "OPS",
                "Operations",
                "Operations department",
                1,
                true,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    // ─── Reads: any authenticated user ────────────────────────────

    @Nested
    @DisplayName("GET /api/companies/{companyId}/departments")
    class GetDepartmentsSecurity {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user (reads are isAuthenticated)")
        void anyAuthenticatedUserCanRead() throws Exception {
            when(departmentService.getAllDepartments(companyId, false)).thenReturn(List.of(buildResponse()));

            mockMvc.perform(get("/api/companies/{companyId}/departments", companyId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/companies/{companyId}/departments", companyId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for GET /{id} for any authenticated user")
        void anyAuthenticatedUserCanReadById() throws Exception {
            when(departmentService.getDepartmentById(companyId, departmentId)).thenReturn(buildResponse());

            mockMvc.perform(get("/api/companies/{companyId}/departments/{id}", companyId, departmentId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated GET /{id}")
        void unauthenticatedReadByIdReturns401() throws Exception {
            mockMvc.perform(get("/api/companies/{companyId}/departments/{id}", companyId, departmentId))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ─── POST /api/companies/{companyId}/departments ──────────────

    @Nested
    @DisplayName("POST /api/companies/{companyId}/departments")
    class CreateDepartmentSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for user with lc-admin role")
        void adminCanCreate() throws Exception {
            when(departmentService.createDepartment(eq(companyId), any(DepartmentRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/companies/{companyId}/departments", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-department"})
        @DisplayName("returns 201 Created for user with lc-department role")
        void domainRoleCanCreate() throws Exception {
            when(departmentService.createDepartment(eq(companyId), any(DepartmentRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/companies/{companyId}/departments", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/departments", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/departments", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/companies/{companyId}/departments/{id} ──────────

    @Nested
    @DisplayName("PUT /api/companies/{companyId}/departments/{id}")
    class UpdateDepartmentSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanUpdate() throws Exception {
            when(departmentService.updateDepartment(eq(companyId), eq(departmentId), any(DepartmentRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/companies/{companyId}/departments/{id}", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-department"})
        @DisplayName("returns 200 OK for user with lc-department role")
        void domainRoleCanUpdate() throws Exception {
            when(departmentService.updateDepartment(eq(companyId), eq(departmentId), any(DepartmentRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/companies/{companyId}/departments/{id}", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(put("/api/companies/{companyId}/departments/{id}", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/companies/{companyId}/departments/{id}", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE /api/companies/{companyId}/departments/{id} ───────

    @Nested
    @DisplayName("DELETE /api/companies/{companyId}/departments/{id}")
    class DeleteDepartmentSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for user with lc-admin role")
        void adminCanDelete() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/departments/{id}", companyId, departmentId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-department"})
        @DisplayName("returns 204 No Content for user with lc-department role")
        void domainRoleCanDelete() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/departments/{id}", companyId, departmentId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/departments/{id}", companyId, departmentId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/departments/{id}", companyId, departmentId))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PATCH /api/companies/{companyId}/departments/{id}/enable ─

    @Nested
    @DisplayName("PATCH /api/companies/{companyId}/departments/{id}/enable")
    class EnableDepartmentSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanEnable() throws Exception {
            when(departmentService.setDepartmentEnabled(companyId, departmentId, true))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/companies/{companyId}/departments/{id}/enable", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-department"})
        @DisplayName("returns 200 OK for user with lc-department role")
        void domainRoleCanEnable() throws Exception {
            when(departmentService.setDepartmentEnabled(companyId, departmentId, true))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/companies/{companyId}/departments/{id}/enable", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch("/api/companies/{companyId}/departments/{id}/enable", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isForbidden());
        }
    }
}
