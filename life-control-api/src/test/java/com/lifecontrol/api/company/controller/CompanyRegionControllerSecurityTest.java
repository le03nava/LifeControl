package com.lifecontrol.api.company.controller;

import com.lifecontrol.api.company.dto.CompanyRegionResponse;
import com.lifecontrol.api.company.dto.CreateCompanyRegionRequest;
import com.lifecontrol.api.company.dto.UpdateCompanyRegionRequest;
import com.lifecontrol.api.company.service.CompanyRegionService;
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

@WebMvcTest(CompanyRegionController.class)
@DisplayName("CompanyRegionController Security — @PreAuthorize class-level authorization")
class CompanyRegionControllerSecurityTest {

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
    private CompanyRegionService companyRegionService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID countryId = UUID.randomUUID();
    private final UUID regionId = UUID.randomUUID();
    private static final String BASE_URL = "/api/companies/{companyId}/countries/{countryId}/regions";

    private CompanyRegionResponse buildRegionResponse() {
        return new CompanyRegionResponse(
                regionId, UUID.randomUUID(), companyId, countryId,
                "NORTE", "Norte", true,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ─── GET /api/companies/{companyId}/countries/{countryId}/regions ──

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetRegions {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for admin")
        void adminCanGetRegions() throws Exception {
            when(companyRegionService.getAllRegions(companyId, countryId, false))
                    .thenReturn(List.of(buildRegionResponse()));

            mockMvc.perform(get(BASE_URL, companyId, countryId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 200 OK for country-role")
        void countryRoleCanGetRegions() throws Exception {
            when(companyRegionService.getAllRegions(companyId, countryId, false))
                    .thenReturn(List.of(buildRegionResponse()));

            mockMvc.perform(get(BASE_URL, companyId, countryId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, countryId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, countryId))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── POST /api/companies/{companyId}/countries/{countryId}/regions ──

    @Nested
    @DisplayName("POST " + BASE_URL)
    class PostRegions {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 201 Created for admin")
        void adminCanCreateRegion() throws Exception {
            var request = new CreateCompanyRegionRequest("NORTE", "Norte");
            when(companyRegionService.createRegion(eq(companyId), eq(countryId), any(CreateCompanyRegionRequest.class)))
                    .thenReturn(buildRegionResponse());

            mockMvc.perform(post(BASE_URL, companyId, countryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 201 Created for country-role")
        void countryRoleCanCreateRegion() throws Exception {
            var request = new CreateCompanyRegionRequest("NORTE", "Norte");
            when(companyRegionService.createRegion(eq(companyId), eq(countryId), any(CreateCompanyRegionRequest.class)))
                    .thenReturn(buildRegionResponse());

            mockMvc.perform(post(BASE_URL, companyId, countryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new CreateCompanyRegionRequest("NORTE", "Norte");
            mockMvc.perform(post(BASE_URL, companyId, countryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            var request = new CreateCompanyRegionRequest("NORTE", "Norte");
            mockMvc.perform(post(BASE_URL, companyId, countryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/companies/{companyId}/countries/{countryId}/regions/{id} ──

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class PutRegions {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for admin")
        void adminCanUpdateRegion() throws Exception {
            var request = new UpdateCompanyRegionRequest("NORTE", "Norte Actualizado");
            when(companyRegionService.updateRegion(eq(companyId), eq(countryId), eq(regionId),
                    any(UpdateCompanyRegionRequest.class)))
                    .thenReturn(buildRegionResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, countryId, regionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 200 OK for country-role")
        void countryRoleCanUpdateRegion() throws Exception {
            var request = new UpdateCompanyRegionRequest("NORTE", "Norte Actualizado");
            when(companyRegionService.updateRegion(eq(companyId), eq(countryId), eq(regionId),
                    any(UpdateCompanyRegionRequest.class)))
                    .thenReturn(buildRegionResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, countryId, regionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new UpdateCompanyRegionRequest("NORTE", "Norte");
            mockMvc.perform(put(BASE_URL + "/{id}", companyId, countryId, regionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            var request = new UpdateCompanyRegionRequest("NORTE", "Norte");
            mockMvc.perform(put(BASE_URL + "/{id}", companyId, countryId, regionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE /api/companies/{companyId}/countries/{countryId}/regions/{id} ──

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class DeleteRegions {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 204 No Content for admin")
        void adminCanDeleteRegion() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, countryId, regionId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 204 No Content for country-role")
        void countryRoleCanDeleteRegion() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, countryId, regionId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, countryId, regionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, countryId, regionId))
                    .andExpect(status().isForbidden());
        }
    }
}
