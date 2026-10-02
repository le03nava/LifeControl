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
import com.lifecontrol.api.hr.dto.PositionRequest;
import com.lifecontrol.api.hr.dto.PositionResponse;
import com.lifecontrol.api.hr.service.PositionService;
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

@WebMvcTest(PositionController.class)
@DisplayName("Position Controller Security — @PreAuthorize method-level authorization")
class PositionControllerSecurityTest {

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
    private PositionService positionService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID departmentId = UUID.randomUUID();
    private final UUID positionId = UUID.randomUUID();

    private PositionRequest buildRequest() {
        return new PositionRequest(departmentId, "OP1", "Operator", "Operator position", null, 1, true);
    }

    private PositionResponse buildResponse() {
        return new PositionResponse(
                positionId,
                companyId,
                departmentId,
                "OP1",
                "Operator",
                "Operator position",
                null,
                1,
                true,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    // ─── Reads: any authenticated user ────────────────────────────

    @Nested
    @DisplayName("GET /api/companies/{companyId}/positions")
    class GetPositionsSecurity {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user (reads are isAuthenticated)")
        void anyAuthenticatedUserCanRead() throws Exception {
            when(positionService.getAllPositions(companyId, null, false)).thenReturn(List.of(buildResponse()));

            mockMvc.perform(get("/api/companies/{companyId}/positions", companyId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/companies/{companyId}/positions", companyId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for GET /{id} for any authenticated user")
        void anyAuthenticatedUserCanReadById() throws Exception {
            when(positionService.getPositionById(companyId, positionId)).thenReturn(buildResponse());

            mockMvc.perform(get("/api/companies/{companyId}/positions/{id}", companyId, positionId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated GET /{id}")
        void unauthenticatedReadByIdReturns401() throws Exception {
            mockMvc.perform(get("/api/companies/{companyId}/positions/{id}", companyId, positionId))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ─── POST /api/companies/{companyId}/positions ────────────────

    @Nested
    @DisplayName("POST /api/companies/{companyId}/positions")
    class CreatePositionSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for user with lc-admin role")
        void adminCanCreate() throws Exception {
            when(positionService.createPosition(eq(companyId), any(PositionRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/companies/{companyId}/positions", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-position"})
        @DisplayName("returns 201 Created for user with lc-position role")
        void domainRoleCanCreate() throws Exception {
            when(positionService.createPosition(eq(companyId), any(PositionRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/companies/{companyId}/positions", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/positions", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/positions", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/companies/{companyId}/positions/{id} ────────────

    @Nested
    @DisplayName("PUT /api/companies/{companyId}/positions/{id}")
    class UpdatePositionSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanUpdate() throws Exception {
            when(positionService.updatePosition(eq(companyId), eq(positionId), any(PositionRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/companies/{companyId}/positions/{id}", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-position"})
        @DisplayName("returns 200 OK for user with lc-position role")
        void domainRoleCanUpdate() throws Exception {
            when(positionService.updatePosition(eq(companyId), eq(positionId), any(PositionRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/companies/{companyId}/positions/{id}", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(put("/api/companies/{companyId}/positions/{id}", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/companies/{companyId}/positions/{id}", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE /api/companies/{companyId}/positions/{id} ─────────

    @Nested
    @DisplayName("DELETE /api/companies/{companyId}/positions/{id}")
    class DeletePositionSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for user with lc-admin role")
        void adminCanDelete() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/positions/{id}", companyId, positionId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-position"})
        @DisplayName("returns 204 No Content for user with lc-position role")
        void domainRoleCanDelete() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/positions/{id}", companyId, positionId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/positions/{id}", companyId, positionId))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PATCH /api/companies/{companyId}/positions/{id}/enable ──

    @Nested
    @DisplayName("PATCH /api/companies/{companyId}/positions/{id}/enable")
    class EnablePositionSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanEnable() throws Exception {
            when(positionService.setPositionEnabled(companyId, positionId, true))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/companies/{companyId}/positions/{id}/enable", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-position"})
        @DisplayName("returns 200 OK for user with lc-position role")
        void domainRoleCanEnable() throws Exception {
            when(positionService.setPositionEnabled(companyId, positionId, true))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/companies/{companyId}/positions/{id}/enable", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch("/api/companies/{companyId}/positions/{id}/enable", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\": true}"))
                    .andExpect(status().isForbidden());
        }
    }
}
