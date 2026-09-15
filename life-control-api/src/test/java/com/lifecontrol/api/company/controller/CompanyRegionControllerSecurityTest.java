package com.lifecontrol.api.company.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.company.dto.CompanyRegionResponse;
import com.lifecontrol.api.company.dto.CreateCompanyRegionRequest;
import com.lifecontrol.api.company.dto.UpdateCompanyRegionRequest;
import com.lifecontrol.api.company.service.CompanyRegionService;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
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

@WebMvcTest(CompanyRegionController.class)
@DisplayName("CompanyRegionController Security — @PreAuthorize class-level authorization")
class CompanyRegionControllerSecurityTest {

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
    private CompanyRegionService companyRegionService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID companyCountryId = UUID.randomUUID();
    private final UUID regionId = UUID.randomUUID();
    private static final String BASE_URL = "/api/companies/{companyId}/countries/{companyCountryId}/regions";

    private CompanyRegionResponse buildRegionResponse() {
        return new CompanyRegionResponse(
                regionId,
                UUID.randomUUID(),
                companyId,
                UUID.randomUUID(),
                "NORTE",
                "Norte",
                true,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    // ─── GET /api/companies/{companyId}/countries/{companyCountryId}/regions ──

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetRegions {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanGetRegions() throws Exception {
            when(companyRegionService.getAllRegions(companyId, companyCountryId, false))
                    .thenReturn(List.of(buildRegionResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId)).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanGetRegions() throws Exception {
            when(companyRegionService.getAllRegions(companyId, companyCountryId, false))
                    .thenReturn(List.of(buildRegionResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId)).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanGetRegions() throws Exception {
            when(companyRegionService.getAllRegions(companyId, companyCountryId, false))
                    .thenReturn(List.of(buildRegionResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId)).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 200 OK for lc-company-region")
        void lcCompanyRegionCanGetRegions() throws Exception {
            when(companyRegionService.getAllRegions(companyId, companyCountryId, false))
                    .thenReturn(List.of(buildRegionResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId)).andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, companyCountryId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, companyCountryId)).andExpect(status().isForbidden());
        }
    }

    // ─── POST /api/companies/{companyId}/countries/{companyCountryId}/regions ──

    @Nested
    @DisplayName("POST " + BASE_URL)
    class PostRegions {

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for lc-admin")
        void lcAdminCanCreateRegion() throws Exception {
            var request = new CreateCompanyRegionRequest("NORTE", "Norte");
            when(companyRegionService.createRegion(
                            eq(companyId), eq(companyCountryId), any(CreateCompanyRegionRequest.class)))
                    .thenReturn(buildRegionResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 201 Created for lc-company")
        void lcCompanyCanCreateRegion() throws Exception {
            var request = new CreateCompanyRegionRequest("NORTE", "Norte");
            when(companyRegionService.createRegion(
                            eq(companyId), eq(companyCountryId), any(CreateCompanyRegionRequest.class)))
                    .thenReturn(buildRegionResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 201 Created for lc-company-country")
        void lcCompanyCountryCanCreateRegion() throws Exception {
            var request = new CreateCompanyRegionRequest("NORTE", "Norte");
            when(companyRegionService.createRegion(
                            eq(companyId), eq(companyCountryId), any(CreateCompanyRegionRequest.class)))
                    .thenReturn(buildRegionResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 403 Forbidden for lc-company-region (cannot create)")
        void lcCompanyRegionCannotCreate() throws Exception {
            var request = new CreateCompanyRegionRequest("NORTE", "Norte");
            // lc-company-region passes @PreAuthorize but service layer denies
            when(companyRegionService.createRegion(
                            eq(companyId), eq(companyCountryId), any(CreateCompanyRegionRequest.class)))
                    .thenThrow(new org.springframework.security.access.AccessDeniedException(
                            "Access denied to company region"));

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new CreateCompanyRegionRequest("NORTE", "Norte");
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            var request = new CreateCompanyRegionRequest("NORTE", "Norte");
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/companies/{companyId}/countries/{companyCountryId}/regions/{id} ──

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class PutRegions {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new UpdateCompanyRegionRequest("NORTE", "Norte");
            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            var request = new UpdateCompanyRegionRequest("NORTE", "Norte");
            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE /api/companies/{companyId}/countries/{companyCountryId}/regions/{id} ──

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class DeleteRegions {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId))
                    .andExpect(status().isForbidden());
        }
    }
}
