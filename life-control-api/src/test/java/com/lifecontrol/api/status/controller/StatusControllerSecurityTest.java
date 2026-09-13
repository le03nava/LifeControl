package com.lifecontrol.api.status.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.status.dto.StatusRequest;
import com.lifecontrol.api.status.dto.StatusResponse;
import com.lifecontrol.api.status.service.StatusService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatusController.class)
@DisplayName("Status Controller Security — @PreAuthorize method-level authorization")
class StatusControllerSecurityTest {

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
            return http
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
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
    private StatusService statusService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID statusId = UUID.randomUUID();
    private final UUID statusTypeId = UUID.randomUUID();

    private StatusRequest buildRequest() {
        return new StatusRequest("ACTIVO", statusTypeId, true);
    }

    private StatusResponse buildResponse() {
        return new StatusResponse(
                statusId, "ACTIVO", statusTypeId, "Order Status",
                true, LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ─── GET /api/statuses ────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/statuses")
    class GetStatusesSecurity {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user (reads are isAuthenticated)")
        void anyAuthenticatedUserCanRead() throws Exception {
            when(statusService.getStatusesByTypeId(statusTypeId)).thenReturn(List.of(buildResponse()));

            mockMvc.perform(get("/api/statuses")
                            .param("statusTypeId", statusTypeId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/statuses")
                            .param("statusTypeId", statusTypeId.toString()))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ─── POST /api/statuses ───────────────────────────────────────

    @Nested
    @DisplayName("POST /api/statuses")
    class CreateStatusSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for user with lc-admin role")
        void adminCanCreate() throws Exception {
            when(statusService.createStatus(any(StatusRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/statuses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-status"})
        @DisplayName("returns 201 Created for user with lc-status role")
        void domainRoleCanCreate() throws Exception {
            when(statusService.createStatus(any(StatusRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/statuses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post("/api/statuses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/statuses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/statuses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/statuses/{id} ───────────────────────────────────

    @Nested
    @DisplayName("PUT /api/statuses/{id}")
    class UpdateStatusSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanUpdate() throws Exception {
            when(statusService.updateStatus(eq(statusId), any(StatusRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/statuses/{id}", statusId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-status"})
        @DisplayName("returns 200 OK for user with lc-status role")
        void domainRoleCanUpdate() throws Exception {
            when(statusService.updateStatus(eq(statusId), any(StatusRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/statuses/{id}", statusId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(put("/api/statuses/{id}", statusId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/statuses/{id}", statusId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE /api/statuses/{id} ────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/statuses/{id}")
    class DeleteStatusSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for user with lc-admin role")
        void adminCanDelete() throws Exception {
            mockMvc.perform(delete("/api/statuses/{id}", statusId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-status"})
        @DisplayName("returns 204 No Content for user with lc-status role")
        void domainRoleCanDelete() throws Exception {
            mockMvc.perform(delete("/api/statuses/{id}", statusId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/statuses/{id}", statusId))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PATCH /api/statuses/{id}/enable ──────────────────────────

    @Nested
    @DisplayName("PATCH /api/statuses/{id}/enable")
    class EnableStatusSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanEnable() throws Exception {
            when(statusService.enableStatus(statusId))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/statuses/{id}/enable", statusId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-status"})
        @DisplayName("returns 200 OK for user with lc-status role")
        void domainRoleCanEnable() throws Exception {
            when(statusService.enableStatus(statusId))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/statuses/{id}/enable", statusId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch("/api/statuses/{id}/enable", statusId))
                    .andExpect(status().isForbidden());
        }
    }
}