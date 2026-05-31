package com.lifecontrol.api.company.controller;

import com.lifecontrol.api.company.dto.CompanyZoneResponse;
import com.lifecontrol.api.company.dto.CreateCompanyZoneRequest;
import com.lifecontrol.api.company.dto.UpdateCompanyZoneRequest;
import com.lifecontrol.api.company.service.CompanyZoneService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CompanyZoneController.class)
@DisplayName("CompanyZoneController Security — @PreAuthorize class-level authorization")
class CompanyZoneControllerSecurityTest {

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
    private CompanyZoneService companyZoneService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID companyCountryId = UUID.randomUUID();
    private final UUID regionId = UUID.randomUUID();
    private final UUID zoneId = UUID.randomUUID();
    private static final String BASE_URL = "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones";

    private CompanyZoneResponse buildZoneResponse() {
        return new CompanyZoneResponse(
                zoneId, regionId, companyCountryId, companyId, UUID.randomUUID(),
                "CEN", "Centro", null, null, true,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ─── GET /api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones ──

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetZones {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for admin")
        void adminCanGetZones() throws Exception {
            when(companyZoneService.getAllZones(companyId, companyCountryId, regionId, false))
                    .thenReturn(List.of(buildZoneResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 200 OK for country-role")
        void countryRoleCanGetZones() throws Exception {
            when(companyZoneService.getAllZones(companyId, companyCountryId, regionId, false))
                    .thenReturn(List.of(buildZoneResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── POST /api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones ──

    @Nested
    @DisplayName("POST " + BASE_URL)
    class PostZones {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 201 Created for admin")
        void adminCanCreateZone() throws Exception {
            var request = new CreateCompanyZoneRequest("CEN", "Centro", null, null);
            when(companyZoneService.createZone(eq(companyId), eq(companyCountryId), eq(regionId), any(CreateCompanyZoneRequest.class)))
                    .thenReturn(buildZoneResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 201 Created for country-role")
        void countryRoleCanCreateZone() throws Exception {
            var request = new CreateCompanyZoneRequest("CEN", "Centro", null, null);
            when(companyZoneService.createZone(eq(companyId), eq(companyCountryId), eq(regionId), any(CreateCompanyZoneRequest.class)))
                    .thenReturn(buildZoneResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new CreateCompanyZoneRequest("CEN", "Centro", null, null);
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            var request = new CreateCompanyZoneRequest("CEN", "Centro", null, null);
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{id} ──

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class PutZones {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for admin")
        void adminCanUpdateZone() throws Exception {
            var request = new UpdateCompanyZoneRequest("CEN", "Centro Actualizado", null, null);
            when(companyZoneService.updateZone(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId),
                    any(UpdateCompanyZoneRequest.class)))
                    .thenReturn(buildZoneResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 200 OK for country-role")
        void countryRoleCanUpdateZone() throws Exception {
            var request = new UpdateCompanyZoneRequest("CEN", "Centro Actualizado", null, null);
            when(companyZoneService.updateZone(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId),
                    any(UpdateCompanyZoneRequest.class)))
                    .thenReturn(buildZoneResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new UpdateCompanyZoneRequest("CEN", "Centro", null, null);
            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            var request = new UpdateCompanyZoneRequest("CEN", "Centro", null, null);
            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── DELETE /api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{id} ──

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class DeleteZones {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 204 No Content for admin")
        void adminCanDeleteZone() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 204 No Content for country-role")
        void countryRoleCanDeleteZone() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PATCH /api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{id} ──

    @Nested
    @DisplayName("PATCH " + BASE_URL + "/{id}")
    class PatchZones {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for admin")
        void adminCanEnableZone() throws Exception {
            when(companyZoneService.enableZone(companyId, companyCountryId, regionId, zoneId))
                    .thenReturn(buildZoneResponse());

            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 200 OK for country-role")
        void countryRoleCanEnableZone() throws Exception {
            when(companyZoneService.enableZone(companyId, companyCountryId, regionId, zoneId))
                    .thenReturn(buildZoneResponse());

            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isForbidden());
        }
    }
}
