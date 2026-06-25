package com.lifecontrol.api.store.controller;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.store.dto.CompanyStoreResponse;
import com.lifecontrol.api.store.dto.CreateCompanyStoreRequest;
import com.lifecontrol.api.store.dto.UpdateCompanyStoreRequest;
import com.lifecontrol.api.store.service.CompanyStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
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

@WebMvcTest(CompanyStoreController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("CompanyStoreController Security — @PreAuthorize class-level authorization")
class CompanyStoreControllerSecurityTest {

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
    private CompanyStoreService companyStoreService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID companyCountryId = UUID.randomUUID();
    private final UUID regionId = UUID.randomUUID();
    private final UUID zoneId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private static final String BASE_URL = "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores";

    private CompanyStoreResponse buildStoreResponse() {
        return new CompanyStoreResponse(
                storeId, companyId, companyCountryId, regionId, zoneId,
                "Tienda Test", "tienda@test.com", "555-0001",
                null,
                true, LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ─── GET /api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores ──

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetStores {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for admin")
        void adminCanGetStores() throws Exception {
            when(companyStoreService.getAllStores(companyId, companyCountryId, regionId, zoneId, false))
                    .thenReturn(List.of(buildStoreResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 200 OK for country-role")
        void countryRoleCanGetStores() throws Exception {
            when(companyStoreService.getAllStores(companyId, companyCountryId, regionId, zoneId, false))
                    .thenReturn(List.of(buildStoreResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanGetStores() throws Exception {
            when(companyStoreService.getAllStores(companyId, companyCountryId, regionId, zoneId, false))
                    .thenReturn(List.of(buildStoreResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanGetStores() throws Exception {
            when(companyStoreService.getAllStores(companyId, companyCountryId, regionId, zoneId, false))
                    .thenReturn(List.of(buildStoreResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanGetStores() throws Exception {
            when(companyStoreService.getAllStores(companyId, companyCountryId, regionId, zoneId, false))
                    .thenReturn(List.of(buildStoreResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 200 OK for lc-company-region")
        void lcCompanyRegionCanGetStores() throws Exception {
            when(companyStoreService.getAllStores(companyId, companyCountryId, regionId, zoneId, false))
                    .thenReturn(List.of(buildStoreResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanGetStores() throws Exception {
            when(companyStoreService.getAllStores(companyId, companyCountryId, regionId, zoneId, false))
                    .thenReturn(List.of(buildStoreResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanGetStores() throws Exception {
            when(companyStoreService.getAllStores(companyId, companyCountryId, regionId, zoneId, false))
                    .thenReturn(List.of(buildStoreResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId))
                    .andExpect(status().isOk());
        }
    }

    // ─── GET /api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{id} ──

    @Nested
    @DisplayName("GET " + BASE_URL + "/{id}")
    class GetStoreById {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for admin")
        void adminCanGetStore() throws Exception {
            when(companyStoreService.getStoreById(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(get(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 200 OK for country-role")
        void countryRoleCanGetStore() throws Exception {
            when(companyStoreService.getStoreById(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(get(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanGetStore() throws Exception {
            when(companyStoreService.getStoreById(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(get(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanGetStore() throws Exception {
            when(companyStoreService.getStoreById(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(get(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanGetStore() throws Exception {
            when(companyStoreService.getStoreById(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(get(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 200 OK for lc-company-region")
        void lcCompanyRegionCanGetStore() throws Exception {
            when(companyStoreService.getStoreById(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(get(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanGetStore() throws Exception {
            when(companyStoreService.getStoreById(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(get(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanGetStore() throws Exception {
            when(companyStoreService.getStoreById(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(get(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── POST /api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores ──

    @Nested
    @DisplayName("POST " + BASE_URL)
    class PostStores {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 201 Created for admin")
        void adminCanCreateStore() throws Exception {
            var request = new CreateCompanyStoreRequest("Nueva Tienda", "nueva@test.com", "555-0002", null);
            when(companyStoreService.createStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), any(CreateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 201 Created for country-role")
        void countryRoleCanCreateStore() throws Exception {
            var request = new CreateCompanyStoreRequest("Nueva Tienda", "nueva@test.com", "555-0002", null);
            when(companyStoreService.createStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), any(CreateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new CreateCompanyStoreRequest("Nueva Tienda", "nueva@test.com", "555-0002", null);
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            var request = new CreateCompanyStoreRequest("Nueva Tienda", "nueva@test.com", "555-0002", null);
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for lc-admin")
        void lcAdminCanCreateStore() throws Exception {
            var request = new CreateCompanyStoreRequest("Nueva Tienda", "nueva@test.com", "555-0002", null);
            when(companyStoreService.createStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), any(CreateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 201 Created for lc-company")
        void lcCompanyCanCreateStore() throws Exception {
            var request = new CreateCompanyStoreRequest("Nueva Tienda", "nueva@test.com", "555-0002", null);
            when(companyStoreService.createStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), any(CreateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 201 Created for lc-company-country")
        void lcCompanyCountryCanCreateStore() throws Exception {
            var request = new CreateCompanyStoreRequest("Nueva Tienda", "nueva@test.com", "555-0002", null);
            when(companyStoreService.createStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), any(CreateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 201 Created for lc-company-region")
        void lcCompanyRegionCanCreateStore() throws Exception {
            var request = new CreateCompanyStoreRequest("Nueva Tienda", "nueva@test.com", "555-0002", null);
            when(companyStoreService.createStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), any(CreateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 201 Created for lc-company-zone")
        void lcCompanyZoneCanCreateStore() throws Exception {
            var request = new CreateCompanyStoreRequest("Nueva Tienda", "nueva@test.com", "555-0002", null);
            when(companyStoreService.createStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), any(CreateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 403 for lc-company-store (create denied at service level)")
        void lcCompanyStoreCreateDenied() throws Exception {
            var request = new CreateCompanyStoreRequest("Nueva Tienda", "nueva@test.com", "555-0002", null);
            when(companyStoreService.createStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), any(CreateCompanyStoreRequest.class)))
                    .thenThrow(new AccessDeniedException("Store-scoped users cannot create stores"));

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── PUT /api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{id} ──

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class PutStores {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for admin")
        void adminCanUpdateStore() throws Exception {
            var request = new UpdateCompanyStoreRequest("Tienda Updated", "updated@test.com", "555-0003", null);
            when(companyStoreService.updateStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), eq(storeId),
                    any(UpdateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 200 OK for country-role")
        void countryRoleCanUpdateStore() throws Exception {
            var request = new UpdateCompanyStoreRequest("Tienda Updated", "updated@test.com", "555-0003", null);
            when(companyStoreService.updateStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), eq(storeId),
                    any(UpdateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new UpdateCompanyStoreRequest("Tienda Updated", "updated@test.com", "555-0003", null);
            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            var request = new UpdateCompanyStoreRequest("Tienda Updated", "updated@test.com", "555-0003", null);
            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanUpdateStore() throws Exception {
            var request = new UpdateCompanyStoreRequest("Tienda Updated", "updated@test.com", "555-0003", null);
            when(companyStoreService.updateStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), eq(storeId),
                    any(UpdateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanUpdateStore() throws Exception {
            var request = new UpdateCompanyStoreRequest("Tienda Updated", "updated@test.com", "555-0003", null);
            when(companyStoreService.updateStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), eq(storeId),
                    any(UpdateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanUpdateStore() throws Exception {
            var request = new UpdateCompanyStoreRequest("Tienda Updated", "updated@test.com", "555-0003", null);
            when(companyStoreService.updateStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), eq(storeId),
                    any(UpdateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 200 OK for lc-company-region")
        void lcCompanyRegionCanUpdateStore() throws Exception {
            var request = new UpdateCompanyStoreRequest("Tienda Updated", "updated@test.com", "555-0003", null);
            when(companyStoreService.updateStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), eq(storeId),
                    any(UpdateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanUpdateStore() throws Exception {
            var request = new UpdateCompanyStoreRequest("Tienda Updated", "updated@test.com", "555-0003", null);
            when(companyStoreService.updateStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), eq(storeId),
                    any(UpdateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanUpdateStore() throws Exception {
            var request = new UpdateCompanyStoreRequest("Tienda Updated", "updated@test.com", "555-0003", null);
            when(companyStoreService.updateStore(eq(companyId), eq(companyCountryId), eq(regionId), eq(zoneId), eq(storeId),
                    any(UpdateCompanyStoreRequest.class)))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(put(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }
    }

    // ─── DELETE /api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{id} ──

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class DeleteStores {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 204 No Content for admin")
        void adminCanDeleteStore() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 204 No Content for country-role")
        void countryRoleCanDeleteStore() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for lc-admin")
        void lcAdminCanDeleteStore() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 204 No Content for lc-company")
        void lcCompanyCanDeleteStore() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 204 No Content for lc-company-country")
        void lcCompanyCountryCanDeleteStore() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 204 No Content for lc-company-region")
        void lcCompanyRegionCanDeleteStore() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 204 No Content for lc-company-zone")
        void lcCompanyZoneCanDeleteStore() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 204 No Content for lc-company-store")
        void lcCompanyStoreCanDeleteStore() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isNoContent());
        }
    }

    // ─── PATCH /api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{id} ──

    @Nested
    @DisplayName("PATCH " + BASE_URL + "/{id}")
    class PatchStores {

        @Test
        @WithMockUser(roles = {"life-control-admin"})
        @DisplayName("returns 200 OK for admin")
        void adminCanEnableStore() throws Exception {
            when(companyStoreService.enableStore(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"life-control-country"})
        @DisplayName("returns 200 OK for country-role")
        void countryRoleCanEnableStore() throws Exception {
            when(companyStoreService.enableStore(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanEnableStore() throws Exception {
            when(companyStoreService.enableStore(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanEnableStore() throws Exception {
            when(companyStoreService.enableStore(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanEnableStore() throws Exception {
            when(companyStoreService.enableStore(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 200 OK for lc-company-region")
        void lcCompanyRegionCanEnableStore() throws Exception {
            when(companyStoreService.enableStore(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanEnableStore() throws Exception {
            when(companyStoreService.enableStore(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanEnableStore() throws Exception {
            when(companyStoreService.enableStore(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(buildStoreResponse());

            mockMvc.perform(patch(BASE_URL + "/{id}", companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }
    }
}
