package com.lifecontrol.api.measureunit.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.measureunit.dto.MeasureUnitRequest;
import com.lifecontrol.api.measureunit.dto.MeasureUnitResponse;
import com.lifecontrol.api.measureunit.service.MeasureUnitService;
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

@WebMvcTest(MeasureUnitController.class)
@DisplayName("MeasureUnit Controller Security — @PreAuthorize method-level authorization")
class MeasureUnitControllerSecurityTest {

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
    private MeasureUnitService measureUnitService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID measureUnitId = UUID.randomUUID();

    private MeasureUnitRequest buildRequest() {
        return new MeasureUnitRequest("Kilogramo", "kg", "PRODUCT", "KGM", "Kilogramo");
    }

    private MeasureUnitResponse buildResponse() {
        return new MeasureUnitResponse(
                measureUnitId, "Kilogramo", "kg", "PRODUCT", "KGM", "Kilogramo",
                true, LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ─── GET /api/measure-units ───────────────────────────────────

    @Nested
    @DisplayName("GET /api/measure-units")
    class GetMeasureUnitsSecurity {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user (reads are isAuthenticated)")
        void anyAuthenticatedUserCanRead() throws Exception {
            when(measureUnitService.getAllMeasureUnits(false)).thenReturn(List.of(buildResponse()));

            mockMvc.perform(get("/api/measure-units"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get("/api/measure-units"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ─── POST /api/measure-units ──────────────────────────────────

    @Nested
    @DisplayName("POST /api/measure-units")
    class CreateMeasureUnitSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for user with lc-admin role")
        void adminCanCreate() throws Exception {
            when(measureUnitService.createMeasureUnit(any(MeasureUnitRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/measure-units")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-measure-unit"})
        @DisplayName("returns 201 Created for user with lc-measure-unit role")
        void domainRoleCanCreate() throws Exception {
            when(measureUnitService.createMeasureUnit(any(MeasureUnitRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(post("/api/measure-units")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(post("/api/measure-units")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/measure-units")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/measure-units")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/measure-units/{id} ──────────────────────────────

    @Nested
    @DisplayName("PUT /api/measure-units/{id}")
    class UpdateMeasureUnitSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanUpdate() throws Exception {
            when(measureUnitService.updateMeasureUnit(eq(measureUnitId), any(MeasureUnitRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/measure-units/{id}", measureUnitId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-measure-unit"})
        @DisplayName("returns 200 OK for user with lc-measure-unit role")
        void domainRoleCanUpdate() throws Exception {
            when(measureUnitService.updateMeasureUnit(eq(measureUnitId), any(MeasureUnitRequest.class)))
                    .thenReturn(buildResponse());

            mockMvc.perform(put("/api/measure-units/{id}", measureUnitId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(put("/api/measure-units/{id}", measureUnitId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/measure-units/{id}", measureUnitId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE /api/measure-units/{id} ───────────────────────────

    @Nested
    @DisplayName("DELETE /api/measure-units/{id}")
    class DeleteMeasureUnitSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for user with lc-admin role")
        void adminCanDelete() throws Exception {
            mockMvc.perform(delete("/api/measure-units/{id}", measureUnitId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-measure-unit"})
        @DisplayName("returns 204 No Content for user with lc-measure-unit role")
        void domainRoleCanDelete() throws Exception {
            mockMvc.perform(delete("/api/measure-units/{id}", measureUnitId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/measure-units/{id}", measureUnitId))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PATCH /api/measure-units/{id}/enable ─────────────────────

    @Nested
    @DisplayName("PATCH /api/measure-units/{id}/enable")
    class EnableMeasureUnitSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanEnable() throws Exception {
            when(measureUnitService.enableMeasureUnit(measureUnitId))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/measure-units/{id}/enable", measureUnitId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-measure-unit"})
        @DisplayName("returns 200 OK for user with lc-measure-unit role")
        void domainRoleCanEnable() throws Exception {
            when(measureUnitService.enableMeasureUnit(measureUnitId))
                    .thenReturn(buildResponse());

            mockMvc.perform(patch("/api/measure-units/{id}/enable", measureUnitId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch("/api/measure-units/{id}/enable", measureUnitId))
                    .andExpect(status().isForbidden());
        }
    }
}