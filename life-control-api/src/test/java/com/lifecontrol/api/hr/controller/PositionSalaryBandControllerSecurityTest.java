package com.lifecontrol.api.hr.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.hr.dto.PositionSalaryBandResponse;
import com.lifecontrol.api.hr.dto.PositionSalaryBandsRequest;
import com.lifecontrol.api.hr.service.PositionSalaryBandService;
import java.math.BigDecimal;
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

@WebMvcTest(PositionSalaryBandController.class)
@DisplayName("PositionSalaryBand Controller Security — @PreAuthorize method-level authorization")
class PositionSalaryBandControllerSecurityTest {

    /**
     * Minimal security configuration that enables method-level security without requiring JWT/OAuth2
     * infrastructure. {@code @WithMockUser} sets up the SecurityContext directly, bypassing the
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

    @MockitoBean
    private PositionSalaryBandService salaryBandService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID positionId = UUID.randomUUID();
    private final UUID juniorId = UUID.randomUUID();

    private PositionSalaryBandResponse buildResponse() {
        return new PositionSalaryBandResponse(
                UUID.randomUUID(),
                positionId,
                juniorId,
                new BigDecimal("100.00"),
                new BigDecimal("200.00"),
                true,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private String path() {
        return "/api/companies/{companyId}/positions/{positionId}/salary-bands";
    }

    @Nested
    @DisplayName("GET /api/companies/{companyId}/positions/{positionId}/salary-bands")
    class GetSalaryBandsSecurity {

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 200 OK for any authenticated user (reads are isAuthenticated)")
        void anyAuthenticatedUserCanRead() throws Exception {
            when(salaryBandService.getSalaryBands(companyId, positionId)).thenReturn(List.of(buildResponse()));

            mockMvc.perform(get(path(), companyId, positionId)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(get(path(), companyId, positionId)).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("PUT /api/companies/{companyId}/positions/{positionId}/salary-bands")
    class ReplaceSalaryBandsSecurity {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanReplace() throws Exception {
            when(salaryBandService.replaceSalaryBands(
                            eq(companyId), eq(positionId), any(PositionSalaryBandsRequest.class)))
                    .thenReturn(List.of(buildResponse()));

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bands\":[]}"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-position"})
        @DisplayName("returns 200 OK for user with lc-position role")
        void domainRoleCanReplace() throws Exception {
            when(salaryBandService.replaceSalaryBands(
                            eq(companyId), eq(positionId), any(PositionSalaryBandsRequest.class)))
                    .thenReturn(List.of(buildResponse()));

            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bands\":[]}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 401 Unauthorized for unauthenticated request")
        void unauthenticatedReturns401() throws Exception {
            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bands\":[]}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(put(path(), companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bands\":[]}"))
                    .andExpect(status().isForbidden());
        }
    }
}
