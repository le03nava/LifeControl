package com.lifecontrol.api.company.controller;

import com.lifecontrol.api.company.dto.CompanyCountryRequest;
import com.lifecontrol.api.company.dto.CompanyCountryResponse;
import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.dto.CompanyResponse;
import com.lifecontrol.api.company.service.CompanyCountryService;
import com.lifecontrol.api.company.service.CompanyService;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CompanyController.class)
@DisplayName("CompanyController Security — @PreAuthorize method-level authorization")
class CompanyControllerSecurityTest {

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
    private CompanyService companyService;

    @MockitoBean
    private CompanyCountryService companyCountryService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private CompanyRequest buildCompanyRequest() {
        return new CompanyRequest(
                "1", "Test Company", 1, "Razon Social",
                "XAXX010101000", "+1234567890", "test@company.com", true
        );
    }

    // ─── GET /api/companies ──────────────────────────────────

    @Nested
    @DisplayName("GET /api/companies")
    class GetCompanies {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for user with life-control-admin role")
        void adminCanGetCompanies() throws Exception {
            var response = new CompanyResponse(
                    UUID.randomUUID(), "1", "Test", 1, "RS",
                    "XAXX010101000", "555", "e@e.com", true,
                    LocalDateTime.now(), LocalDateTime.now()
            );
            var page = new PageImpl<>(List.of(response), PageRequest.of(0, 12), 1);
            when(companyService.getAllCompanies(any(), eq(null))).thenReturn(page);

            mockMvc.perform(get("/api/companies"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get("/api/companies"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get("/api/companies"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 200 OK for user with life-control-country role")
        void countryRoleCanGetCompanies() throws Exception {
            var response = new CompanyResponse(
                    UUID.randomUUID(), "1", "Test", 1, "RS",
                    "XAXX010101000", "555", "e@e.com", true,
                    LocalDateTime.now(), LocalDateTime.now()
            );
            var page = new PageImpl<>(List.of(response), PageRequest.of(0, 12), 1);
            when(companyService.getAllCompanies(any(), eq(null))).thenReturn(page);

            mockMvc.perform(get("/api/companies"))
                    .andExpect(status().isOk());
        }
    }

    // ─── POST /api/companies ─────────────────────────────────

    @Nested
    @DisplayName("POST /api/companies")
    class PostCompanies {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 201 Created for user with life-control-admin role")
        void adminCanCreateCompany() throws Exception {
            var response = new CompanyResponse(
                    UUID.randomUUID(), "1", "Test", 1, "RS",
                    "XAXX010101000", "555", "e@e.com", true,
                    LocalDateTime.now(), LocalDateTime.now()
            );
            when(companyService.createCompany(any())).thenReturn(response);

            mockMvc.perform(post("/api/companies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCompanyRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/companies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCompanyRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(post("/api/companies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCompanyRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 403 Forbidden for country-role (admin-only write)")
        void countryRoleCannotCreateCompany() throws Exception {
            mockMvc.perform(post("/api/companies")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCompanyRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/companies/{id} ──────────────────────────────

    @Nested
    @DisplayName("PUT /api/companies/{id}")
    class PutCompanies {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for user with life-control-admin role")
        void adminCanUpdateCompany() throws Exception {
            var response = new CompanyResponse(
                    UUID.randomUUID(), "1", "Test", 1, "RS",
                    "XAXX010101000", "555", "e@e.com", true,
                    LocalDateTime.now(), LocalDateTime.now()
            );
            when(companyService.updateCompany(any(), any())).thenReturn(response);

            mockMvc.perform(put("/api/companies/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCompanyRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 403 Forbidden for country-role (admin-only write)")
        void countryRoleCannotUpdateCompany() throws Exception {
            mockMvc.perform(put("/api/companies/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCompanyRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(put("/api/companies/{id}", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCompanyRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE /api/companies/{id} ───────────────────────────

    @Nested
    @DisplayName("DELETE /api/companies/{id}")
    class DeleteCompanies {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 204 No Content for user with life-control-admin role")
        void adminCanDeleteCompany() throws Exception {
            var id = UUID.randomUUID();
            mockMvc.perform(delete("/api/companies/{id}", id))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 403 Forbidden for country-role (admin-only write)")
        void countryRoleCannotDeleteCompany() throws Exception {
            mockMvc.perform(delete("/api/companies/{id}", UUID.randomUUID()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/companies/{id}", UUID.randomUUID()))
                    .andExpect(status().isForbidden());
        }
    }
}
