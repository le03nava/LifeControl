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
import com.lifecontrol.api.hr.dto.SeniorityLevelRequest;
import com.lifecontrol.api.hr.dto.SeniorityLevelResponse;
import com.lifecontrol.api.hr.service.SeniorityLevelService;
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

@WebMvcTest(SeniorityLevelController.class)
@DisplayName("SeniorityLevel Controller Security — @PreAuthorize method-level authorization")
class SeniorityLevelControllerSecurityTest {

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
    private SeniorityLevelService seniorityLevelService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID seniorityLevelId = UUID.randomUUID();

    private SeniorityLevelRequest buildRequest() {
        return new SeniorityLevelRequest("L1", "Junior", 1, true);
    }

    private SeniorityLevelResponse buildResponse() {
        return new SeniorityLevelResponse(
                seniorityLevelId, "L1", "Junior", 1, true, LocalDateTime.now(), LocalDateTime.now());
    }

    // ─── GET /api/seniority-levels ───────────────────────────────

    @Nested
    @DisplayName("GET /api/seniority-levels")
    class GetSeniorityLevelsSecurity {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user (reads are isAuthenticated)")
        void anyAuthenticatedUserCanRead() throws Exception {
            when(seniorityLevelService.getAllSeniorityLevels(false)).thenReturn(List.of(buildResponse()));

            mockMvc.perform(get("/api/seniority-levels")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/seniority-levels")).andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for GET /{id} for any authenticated user")
        void anyAuthenticatedUserCanReadById() throws Exception {
            when(seniorityLevelService.getSeniorityLevelById(seniorityLevelId)).thenReturn(buildResponse());

            mockMvc.perform(get("/api/seniority-levels/{id}", seniorityLevelId)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated GET /{id}")
        void unauthenticatedReadByIdReturns401() throws Exception {
            mockMvc.perform(get("/api/seniority-levels/{id}", seniorityLevelId)).andExpect(status().isUnauthorized());
        }
    }

    // ─── POST /api/seniority-levels ──────────────────────────────

    @Nested
    @DisplayName("POST /api/seniority-levels")
    class CreateSeniorityLevelSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for user with lc-admin role")
        void adminCanCreate() throws Exception {
            when(seniorityLevelService.createSeniorityLevel(any(SeniorityLevelRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/seniority-levels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-seniority-level"})
        @DisplayName("returns 201 Created for user with lc-seniority-level role")
        void domainRoleCanCreate() throws Exception {
            when(seniorityLevelService.createSeniorityLevel(any(SeniorityLevelRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/seniority-levels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post("/api/seniority-levels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/seniority-levels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/seniority-levels/{id} ──────────────────────────

    @Nested
    @DisplayName("PUT /api/seniority-levels/{id}")
    class UpdateSeniorityLevelSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanUpdate() throws Exception {
            when(seniorityLevelService.updateSeniorityLevel(eq(seniorityLevelId), any(SeniorityLevelRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/seniority-levels/{id}", seniorityLevelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-seniority-level"})
        @DisplayName("returns 200 OK for user with lc-seniority-level role")
        void domainRoleCanUpdate() throws Exception {
            when(seniorityLevelService.updateSeniorityLevel(eq(seniorityLevelId), any(SeniorityLevelRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/seniority-levels/{id}", seniorityLevelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(put("/api/seniority-levels/{id}", seniorityLevelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/seniority-levels/{id}", seniorityLevelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE /api/seniority-levels/{id} ───────────────────────

    @Nested
    @DisplayName("DELETE /api/seniority-levels/{id}")
    class DeleteSeniorityLevelSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for user with lc-admin role")
        void adminCanDelete() throws Exception {
            mockMvc.perform(delete("/api/seniority-levels/{id}", seniorityLevelId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-seniority-level"})
        @DisplayName("returns 204 No Content for user with lc-seniority-level role")
        void domainRoleCanDelete() throws Exception {
            mockMvc.perform(delete("/api/seniority-levels/{id}", seniorityLevelId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/seniority-levels/{id}", seniorityLevelId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/seniority-levels/{id}", seniorityLevelId))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PATCH /api/seniority-levels/{id}/enable ─────────────────

    @Nested
    @DisplayName("PATCH /api/seniority-levels/{id}/enable")
    class EnableSeniorityLevelSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanEnable() throws Exception {
            when(seniorityLevelService.setSeniorityLevelEnabled(seniorityLevelId, true))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/seniority-levels/{id}/enable", seniorityLevelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-seniority-level"})
        @DisplayName("returns 200 OK for user with lc-seniority-level role")
        void domainRoleCanEnable() throws Exception {
            when(seniorityLevelService.setSeniorityLevelEnabled(seniorityLevelId, true))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/seniority-levels/{id}/enable", seniorityLevelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch("/api/seniority-levels/{id}/enable", seniorityLevelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isForbidden());
        }
    }
}
