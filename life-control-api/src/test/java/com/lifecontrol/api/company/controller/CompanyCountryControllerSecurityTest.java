package com.lifecontrol.api.company.controller;

import com.lifecontrol.api.company.dto.CompanyCountryRequest;
import com.lifecontrol.api.company.dto.CompanyCountryResponse;
import com.lifecontrol.api.company.service.CompanyCountryService;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CompanyCountryController.class)
@DisplayName("CompanyCountryController Security — @PreAuthorize authorization")
class CompanyCountryControllerSecurityTest {

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
    private CompanyCountryService companyCountryService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID countryRelationId = UUID.randomUUID();
    private static final String BASE_URL = "/api/companies/{companyId}/countries";

    private CompanyCountryRequest buildCountryRequest() {
        return new CompanyCountryRequest("MX", "México");
    }

    private CompanyCountryResponse buildCountryResponse() {
        return new CompanyCountryResponse(
                countryRelationId, companyId, UUID.randomUUID(),
                "MX", "México", "Mexican market",
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ─── GET /api/companies/{companyId}/countries ────────────────

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetCountries {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanGet() throws Exception {
            when(companyCountryService.getCountriesByCompanyId(companyId))
                    .thenReturn(List.of(buildCountryResponse()));

            mockMvc.perform(get(BASE_URL, companyId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanGet() throws Exception {
            when(companyCountryService.getCountriesByCompanyId(companyId))
                    .thenReturn(List.of(buildCountryResponse()));

            mockMvc.perform(get(BASE_URL, companyId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanGet() throws Exception {
            when(companyCountryService.getCountriesByCompanyId(companyId))
                    .thenReturn(List.of(buildCountryResponse()));

            mockMvc.perform(get(BASE_URL, companyId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-read"})
        @DisplayName("returns 200 OK for lc-company-read")
        void lcCompanyReadCanGet() throws Exception {
            when(companyCountryService.getCountriesByCompanyId(companyId))
                    .thenReturn(List.of(buildCountryResponse()));

            mockMvc.perform(get(BASE_URL, companyId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for wrong role")
        void wrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void noRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── POST /api/companies/{companyId}/countries ───────────────

    @Nested
    @DisplayName("POST " + BASE_URL)
    class PostCountry {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for lc-admin")
        void lcAdminCanPost() throws Exception {
            when(companyCountryService.addCountryToCompany(any(), any()))
                    .thenReturn(buildCountryResponse());

            mockMvc.perform(post(BASE_URL, companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCountryRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 201 Created for lc-company")
        void lcCompanyCanPost() throws Exception {
            when(companyCountryService.addCountryToCompany(any(), any()))
                    .thenReturn(buildCountryResponse());

            mockMvc.perform(post(BASE_URL, companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCountryRequest())))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 403 Forbidden for lc-company-country")
        void lcCompanyCountryPostIsDenied() throws Exception {
            mockMvc.perform(post(BASE_URL, companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCountryRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-read"})
        @DisplayName("returns 403 Forbidden for lc-company-read")
        void lcCompanyReadPostIsDenied() throws Exception {
            mockMvc.perform(post(BASE_URL, companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCountryRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for wrong role")
        void wrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(post(BASE_URL, companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCountryRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void noRoleGetsForbidden() throws Exception {
            mockMvc.perform(post(BASE_URL, companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCountryRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/companies/{companyId}/countries/{id} ───────────

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class PutCountry {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanPut() throws Exception {
            when(companyCountryService.updateCountry(any(), any(), any()))
                    .thenReturn(buildCountryResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, countryRelationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCountryRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanPut() throws Exception {
            when(companyCountryService.updateCountry(any(), any(), any()))
                    .thenReturn(buildCountryResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, countryRelationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCountryRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanPut() throws Exception {
            when(companyCountryService.updateCountry(any(), any(), any()))
                    .thenReturn(buildCountryResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, countryRelationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCountryRequest())))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-read"})
        @DisplayName("returns 403 Forbidden for lc-company-read")
        void lcCompanyReadPutIsDenied() throws Exception {
            mockMvc.perform(put(BASE_URL + "/{id}", companyId, countryRelationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCountryRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for wrong role")
        void wrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(put(BASE_URL + "/{id}", companyId, countryRelationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCountryRequest())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void noRoleGetsForbidden() throws Exception {
            mockMvc.perform(put(BASE_URL + "/{id}", companyId, countryRelationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCountryRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE /api/companies/{companyId}/countries/{id} ────────

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class DeleteCountry {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for lc-admin")
        void lcAdminCanDelete() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, countryRelationId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 204 No Content for lc-company")
        void lcCompanyCanDelete() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, countryRelationId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 204 No Content for lc-company-country")
        void lcCompanyCountryCanDelete() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, countryRelationId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company-read"})
        @DisplayName("returns 403 Forbidden for lc-company-read")
        void lcCompanyReadDeleteIsDenied() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, countryRelationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 Forbidden for wrong role")
        void wrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, countryRelationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 Forbidden for authenticated user with no roles")
        void noRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, countryRelationId))
                    .andExpect(status().isForbidden());
        }
    }
}
