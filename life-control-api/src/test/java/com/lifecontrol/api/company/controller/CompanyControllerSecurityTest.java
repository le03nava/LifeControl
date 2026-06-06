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
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
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
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for user with lc-company role")
        void lcCompanyRoleCanGetCompanies() throws Exception {
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

    // ─── GET /api/companies/{id} ──────────────────────────────

    @Nested
    @DisplayName("GET /api/companies/{id}")
    class GetCompanyById {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
        void adminCanGetCompanyById() throws Exception {
            var response = new CompanyResponse(
                    UUID.randomUUID(), "1", "Test", 1, "RS",
                    "XAXX010101000", "555", "e@e.com", true,
                    LocalDateTime.now(), LocalDateTime.now()
            );
            when(companyService.getCompanyById(any())).thenReturn(response);

            mockMvc.perform(get("/api/companies/{id}", UUID.randomUUID()))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for user with lc-company role")
        void lcCompanyCanGetCompanyById() throws Exception {
            var response = new CompanyResponse(
                    UUID.randomUUID(), "1", "Test", 1, "RS",
                    "XAXX010101000", "555", "e@e.com", true,
                    LocalDateTime.now(), LocalDateTime.now()
            );
            when(companyService.getCompanyById(any())).thenReturn(response);

            mockMvc.perform(get("/api/companies/{id}", UUID.randomUUID()))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get("/api/companies/{id}", UUID.randomUUID()))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── POST /api/companies ─────────────────────────────────

    @Nested
    @DisplayName("POST /api/companies")
    class PostCompanies {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for user with lc-admin role")
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
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 201 Created for user with lc-company role")
        void lcCompanyCanCreateCompany() throws Exception {
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
    }

    // ─── PUT /api/companies/{id} ──────────────────────────────

    @Nested
    @DisplayName("PUT /api/companies/{id}")
    class PutCompanies {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for user with lc-admin role")
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
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for user with lc-company role")
        void lcCompanyCanUpdateCompany() throws Exception {
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
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for user with lc-admin role")
        void adminCanDeleteCompany() throws Exception {
            var id = UUID.randomUUID();
            mockMvc.perform(delete("/api/companies/{id}", id))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 204 No Content for user with lc-company role")
        void lcCompanyCanDeleteCompany() throws Exception {
            mockMvc.perform(delete("/api/companies/{id}", UUID.randomUUID()))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete("/api/companies/{id}", UUID.randomUUID()))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── CompanyCountry nested endpoints ──────────────────────

    @Nested
    @DisplayName("CompanyCountry nested endpoints — three-tier role access")
    class CompanyCountryEndpoints {

        private CompanyCountryRequest buildCountryRequest() {
            return new CompanyCountryRequest("MX", "México");
        }

        private CompanyCountryResponse buildCountryResponse() {
            return new CompanyCountryResponse(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "MX", "México", "Mexican market",
                    LocalDateTime.now(), LocalDateTime.now()
            );
        }

        // ─── GET /api/companies/{companyId}/countries ──────────

        @Nested
        @DisplayName("GET /api/companies/{companyId}/countries")
        class GetCompanyCountries {

            @Test
            @WithMockUser(roles = {"lc-admin"})
            @DisplayName("returns 200 OK for lc-admin")
            void adminCanGet() throws Exception {
                when(companyCountryService.getCountriesByCompanyId(any()))
                        .thenReturn(List.of(buildCountryResponse()));

                mockMvc.perform(get("/api/companies/{companyId}/countries", UUID.randomUUID()))
                        .andExpect(status().isOk());
            }

            @Test
            @WithMockUser(roles = {"lc-company"})
            @DisplayName("returns 200 OK for lc-company")
            void companyCanGet() throws Exception {
                when(companyCountryService.getCountriesByCompanyId(any()))
                        .thenReturn(List.of(buildCountryResponse()));

                mockMvc.perform(get("/api/companies/{companyId}/countries", UUID.randomUUID()))
                        .andExpect(status().isOk());
            }

            @Test
            @WithMockUser(roles = {"lc-company-country"})
            @DisplayName("returns 200 OK for lc-company-country")
            void companyCountryCanGet() throws Exception {
                when(companyCountryService.getCountriesByCompanyId(any()))
                        .thenReturn(List.of(buildCountryResponse()));

                mockMvc.perform(get("/api/companies/{companyId}/countries", UUID.randomUUID()))
                        .andExpect(status().isOk());
            }

            @Test
            @WithMockUser(roles = {"other-role"})
            @DisplayName("returns 403 Forbidden for wrong role")
            void wrongRoleGetsForbidden() throws Exception {
                mockMvc.perform(get("/api/companies/{companyId}/countries", UUID.randomUUID()))
                        .andExpect(status().isForbidden());
            }

            @Test
            @WithMockUser
            @DisplayName("returns 403 Forbidden for authenticated user with no roles")
            void noRoleGetsForbidden() throws Exception {
                mockMvc.perform(get("/api/companies/{companyId}/countries", UUID.randomUUID()))
                        .andExpect(status().isForbidden());
            }
        }

        // ─── POST /api/companies/{companyId}/countries ─────────

        @Nested
        @DisplayName("POST /api/companies/{companyId}/countries")
        class PostCompanyCountry {

            @Test
            @WithMockUser(roles = {"lc-admin"})
            @DisplayName("returns 201 Created for lc-admin")
            void adminCanPost() throws Exception {
                when(companyCountryService.addCountryToCompany(any(), any()))
                        .thenReturn(buildCountryResponse());

                mockMvc.perform(post("/api/companies/{companyId}/countries", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(buildCountryRequest())))
                        .andExpect(status().isCreated());
            }

            @Test
            @WithMockUser(roles = {"lc-company"})
            @DisplayName("returns 201 Created for lc-company")
            void companyCanPost() throws Exception {
                when(companyCountryService.addCountryToCompany(any(), any()))
                        .thenReturn(buildCountryResponse());

                mockMvc.perform(post("/api/companies/{companyId}/countries", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(buildCountryRequest())))
                        .andExpect(status().isCreated());
            }

            @Test
            @WithMockUser(roles = {"lc-company-country"})
            @DisplayName("returns 403 Forbidden for lc-company-country (POST denied)")
            void companyCountryPostIsDenied() throws Exception {
                mockMvc.perform(post("/api/companies/{companyId}/countries", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(buildCountryRequest())))
                        .andExpect(status().isForbidden());
            }

            @Test
            @WithMockUser(roles = {"other-role"})
            @DisplayName("returns 403 Forbidden for wrong role")
            void wrongRoleGetsForbidden() throws Exception {
                mockMvc.perform(post("/api/companies/{companyId}/countries", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(buildCountryRequest())))
                        .andExpect(status().isForbidden());
            }

            @Test
            @WithMockUser
            @DisplayName("returns 403 Forbidden for authenticated user with no roles")
            void noRoleGetsForbidden() throws Exception {
                mockMvc.perform(post("/api/companies/{companyId}/countries", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(buildCountryRequest())))
                        .andExpect(status().isForbidden());
            }
        }

        // ─── PUT /api/companies/{companyId}/countries/{id} ─────

        @Nested
        @DisplayName("PUT /api/companies/{companyId}/countries/{id}")
        class PutCompanyCountry {

            @Test
            @WithMockUser(roles = {"lc-admin"})
            @DisplayName("returns 200 OK for lc-admin")
            void adminCanPut() throws Exception {
                when(companyCountryService.updateCountry(any(), any(), any()))
                        .thenReturn(buildCountryResponse());

                mockMvc.perform(put("/api/companies/{companyId}/countries/{id}",
                                UUID.randomUUID(), UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(buildCountryRequest())))
                        .andExpect(status().isOk());
            }

            @Test
            @WithMockUser(roles = {"lc-company"})
            @DisplayName("returns 200 OK for lc-company")
            void companyCanPut() throws Exception {
                when(companyCountryService.updateCountry(any(), any(), any()))
                        .thenReturn(buildCountryResponse());

                mockMvc.perform(put("/api/companies/{companyId}/countries/{id}",
                                UUID.randomUUID(), UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(buildCountryRequest())))
                        .andExpect(status().isOk());
            }

            @Test
            @WithMockUser(roles = {"lc-company-country"})
            @DisplayName("returns 200 OK for lc-company-country")
            void companyCountryCanPut() throws Exception {
                when(companyCountryService.updateCountry(any(), any(), any()))
                        .thenReturn(buildCountryResponse());

                mockMvc.perform(put("/api/companies/{companyId}/countries/{id}",
                                UUID.randomUUID(), UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(buildCountryRequest())))
                        .andExpect(status().isOk());
            }

            @Test
            @WithMockUser(roles = {"other-role"})
            @DisplayName("returns 403 Forbidden for wrong role")
            void wrongRoleGetsForbidden() throws Exception {
                mockMvc.perform(put("/api/companies/{companyId}/countries/{id}",
                                UUID.randomUUID(), UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(buildCountryRequest())))
                        .andExpect(status().isForbidden());
            }

            @Test
            @WithMockUser
            @DisplayName("returns 403 Forbidden for authenticated user with no roles")
            void noRoleGetsForbidden() throws Exception {
                mockMvc.perform(put("/api/companies/{companyId}/countries/{id}",
                                UUID.randomUUID(), UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(buildCountryRequest())))
                        .andExpect(status().isForbidden());
            }
        }

        // ─── DELETE /api/companies/{companyId}/countries/{id} ──

        @Nested
        @DisplayName("DELETE /api/companies/{companyId}/countries/{id}")
        class DeleteCompanyCountry {

            @Test
            @WithMockUser(roles = {"lc-admin"})
            @DisplayName("returns 204 No Content for lc-admin")
            void adminCanDelete() throws Exception {
                mockMvc.perform(delete("/api/companies/{companyId}/countries/{id}",
                                UUID.randomUUID(), UUID.randomUUID()))
                        .andExpect(status().isNoContent());
            }

            @Test
            @WithMockUser(roles = {"lc-company"})
            @DisplayName("returns 204 No Content for lc-company")
            void companyCanDelete() throws Exception {
                mockMvc.perform(delete("/api/companies/{companyId}/countries/{id}",
                                UUID.randomUUID(), UUID.randomUUID()))
                        .andExpect(status().isNoContent());
            }

            @Test
            @WithMockUser(roles = {"lc-company-country"})
            @DisplayName("returns 204 No Content for lc-company-country")
            void companyCountryCanDelete() throws Exception {
                mockMvc.perform(delete("/api/companies/{companyId}/countries/{id}",
                                UUID.randomUUID(), UUID.randomUUID()))
                        .andExpect(status().isNoContent());
            }

            @Test
            @WithMockUser(roles = {"other-role"})
            @DisplayName("returns 403 Forbidden for wrong role")
            void wrongRoleGetsForbidden() throws Exception {
                mockMvc.perform(delete("/api/companies/{companyId}/countries/{id}",
                                UUID.randomUUID(), UUID.randomUUID()))
                        .andExpect(status().isForbidden());
            }

            @Test
            @WithMockUser
            @DisplayName("returns 403 Forbidden for authenticated user with no roles")
            void noRoleGetsForbidden() throws Exception {
                mockMvc.perform(delete("/api/companies/{companyId}/countries/{id}",
                                UUID.randomUUID(), UUID.randomUUID()))
                        .andExpect(status().isForbidden());
            }
        }
    }
}
