package com.lifecontrol.api.store.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.store.dto.CreateStoreLocationRequest;
import com.lifecontrol.api.store.dto.StoreLocationResponse;
import com.lifecontrol.api.store.dto.UpdateStoreLocationRequest;
import com.lifecontrol.api.store.service.StoreLocationService;
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
import org.springframework.context.annotation.Import;
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

@WebMvcTest({StoreLocationController.class, StoreLocationFlatController.class})
@Import(GlobalExceptionHandler.class)
@DisplayName("StoreLocationController Security — @PreAuthorize method-level authorization")
class StoreLocationControllerSecurityTest {

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
    private StoreLocationService storeLocationService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID companyCountryId = UUID.randomUUID();
    private final UUID regionId = UUID.randomUUID();
    private final UUID zoneId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID areaId = UUID.randomUUID();
    private final UUID storeZoneId = UUID.randomUUID();
    private final UUID storeLocationId = UUID.randomUUID();

    private static final String BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas/{areaId}/store-zones/{storeZoneId}/store-locations";

    private static final String FLAT_URL = "/api/store-locations";

    private StoreLocationResponse buildStoreLocationResponse() {
        return new StoreLocationResponse(
                storeLocationId,
                storeZoneId,
                areaId,
                storeId,
                companyId,
                companyCountryId,
                regionId,
                zoneId,
                "L01",
                "Estante",
                "Descripción",
                1,
                true,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetLocations {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanList() throws Exception {
            when(storeLocationService.getAllLocations(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, false))
                    .thenReturn(List.of(buildStoreLocationResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanList() throws Exception {
            when(storeLocationService.getAllLocations(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanList() throws Exception {
            when(storeLocationService.getAllLocations(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 200 OK for lc-company-region")
        void lcCompanyRegionCanList() throws Exception {
            when(storeLocationService.getAllLocations(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanList() throws Exception {
            when(storeLocationService.getAllLocations(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanList() throws Exception {
            when(storeLocationService.getAllLocations(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 200 OK for lc-company-store-read")
        void lcCompanyStoreReadCanList() throws Exception {
            when(storeLocationService.getAllLocations(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{storeLocationId}")
    class GetLocationById {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanGetById() throws Exception {
            when(storeLocationService.getLocationById(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanGetById() throws Exception {
            when(storeLocationService.getLocationById(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanGetById() throws Exception {
            when(storeLocationService.getLocationById(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 200 OK for lc-company-region")
        void lcCompanyRegionCanGetById() throws Exception {
            when(storeLocationService.getLocationById(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanGetById() throws Exception {
            when(storeLocationService.getLocationById(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanGetById() throws Exception {
            when(storeLocationService.getLocationById(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 200 OK for lc-company-store-read")
        void lcCompanyStoreReadCanGetById() throws Exception {
            when(storeLocationService.getLocationById(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST " + BASE_URL)
    class PostLocations {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", null, 1);
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", null, 1);
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for read-only role")
        void readOnlyRoleCannotCreate() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", null, 1);
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for lc-admin")
        void lcAdminCanCreate() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", null, 1);
            when(storeLocationService.createLocation(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            any(CreateStoreLocationRequest.class)))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 201 Created for lc-company")
        void lcCompanyCanCreate() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", null, 1);
            when(storeLocationService.createLocation(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            any(CreateStoreLocationRequest.class)))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 201 Created for lc-company-country")
        void lcCompanyCountryCanCreate() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", null, 1);
            when(storeLocationService.createLocation(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            any(CreateStoreLocationRequest.class)))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 201 Created for lc-company-region")
        void lcCompanyRegionCanCreate() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", null, 1);
            when(storeLocationService.createLocation(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            any(CreateStoreLocationRequest.class)))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 201 Created for lc-company-zone")
        void lcCompanyZoneCanCreate() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", null, 1);
            when(storeLocationService.createLocation(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            any(CreateStoreLocationRequest.class)))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 201 Created for lc-company-store")
        void lcCompanyStoreCanCreate() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", null, 1);
            when(storeLocationService.createLocation(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            any(CreateStoreLocationRequest.class)))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{storeLocationId}")
    class PutLocations {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new UpdateStoreLocationRequest("L02", "Estante", null, 2);
            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    storeLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            var request = new UpdateStoreLocationRequest("L02", "Estante", null, 2);
            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    storeLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for read-only role")
        void readOnlyRoleCannotUpdate() throws Exception {
            var request = new UpdateStoreLocationRequest("L02", "Estante", null, 2);
            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    storeLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanUpdate() throws Exception {
            var request = new UpdateStoreLocationRequest("L02", "Estante", null, 2);
            when(storeLocationService.updateLocation(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            eq(storeLocationId),
                            any(UpdateStoreLocationRequest.class)))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    storeLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanUpdate() throws Exception {
            var request = new UpdateStoreLocationRequest("L02", "Estante", null, 2);
            when(storeLocationService.updateLocation(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            eq(storeLocationId),
                            any(UpdateStoreLocationRequest.class)))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    storeLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanUpdate() throws Exception {
            var request = new UpdateStoreLocationRequest("L02", "Estante", null, 2);
            when(storeLocationService.updateLocation(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            eq(storeLocationId),
                            any(UpdateStoreLocationRequest.class)))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    storeLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 200 OK for lc-company-region")
        void lcCompanyRegionCanUpdate() throws Exception {
            var request = new UpdateStoreLocationRequest("L02", "Estante", null, 2);
            when(storeLocationService.updateLocation(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            eq(storeLocationId),
                            any(UpdateStoreLocationRequest.class)))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    storeLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanUpdate() throws Exception {
            var request = new UpdateStoreLocationRequest("L02", "Estante", null, 2);
            when(storeLocationService.updateLocation(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            eq(storeLocationId),
                            any(UpdateStoreLocationRequest.class)))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    storeLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanUpdate() throws Exception {
            var request = new UpdateStoreLocationRequest("L02", "Estante", null, 2);
            when(storeLocationService.updateLocation(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            eq(storeLocationId),
                            any(UpdateStoreLocationRequest.class)))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId,
                                    storeLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{storeLocationId}")
    class DeleteLocations {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for read-only role")
        void readOnlyRoleCannotDelete() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for lc-admin")
        void lcAdminCanDelete() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 204 No Content for lc-company")
        void lcCompanyCanDelete() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 204 No Content for lc-company-country")
        void lcCompanyCountryCanDelete() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 204 No Content for lc-company-region")
        void lcCompanyRegionCanDelete() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 204 No Content for lc-company-zone")
        void lcCompanyZoneCanDelete() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 204 No Content for lc-company-store")
        void lcCompanyStoreCanDelete() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{storeLocationId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("PATCH " + BASE_URL + "/{storeLocationId}/enable")
    class PatchLocations {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(patch(
                            BASE_URL + "/{storeLocationId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(patch(
                            BASE_URL + "/{storeLocationId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for read-only role")
        void readOnlyRoleCannotEnable() throws Exception {
            mockMvc.perform(patch(
                            BASE_URL + "/{storeLocationId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanEnable() throws Exception {
            when(storeLocationService.enableLocation(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(patch(
                            BASE_URL + "/{storeLocationId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanEnable() throws Exception {
            when(storeLocationService.enableLocation(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(patch(
                            BASE_URL + "/{storeLocationId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanEnable() throws Exception {
            when(storeLocationService.enableLocation(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(patch(
                            BASE_URL + "/{storeLocationId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 200 OK for lc-company-region")
        void lcCompanyRegionCanEnable() throws Exception {
            when(storeLocationService.enableLocation(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(patch(
                            BASE_URL + "/{storeLocationId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanEnable() throws Exception {
            when(storeLocationService.enableLocation(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(patch(
                            BASE_URL + "/{storeLocationId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanEnable() throws Exception {
            when(storeLocationService.enableLocation(
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .thenReturn(buildStoreLocationResponse());

            mockMvc.perform(patch(
                            BASE_URL + "/{storeLocationId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId,
                            storeLocationId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /api/store-locations/{storeLocationId} (flat)")
    class GetLocationByIdFlat {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no matching role")
        void userWithNoMatchingRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(FLAT_URL + "/{storeLocationId}", storeLocationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(FLAT_URL + "/{storeLocationId}", storeLocationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanGetById() throws Exception {
            when(storeLocationService.getLocationById(storeLocationId)).thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(FLAT_URL + "/{storeLocationId}", storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanGetById() throws Exception {
            when(storeLocationService.getLocationById(storeLocationId)).thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(FLAT_URL + "/{storeLocationId}", storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanGetById() throws Exception {
            when(storeLocationService.getLocationById(storeLocationId)).thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(FLAT_URL + "/{storeLocationId}", storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 200 OK for lc-company-region")
        void lcCompanyRegionCanGetById() throws Exception {
            when(storeLocationService.getLocationById(storeLocationId)).thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(FLAT_URL + "/{storeLocationId}", storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanGetById() throws Exception {
            when(storeLocationService.getLocationById(storeLocationId)).thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(FLAT_URL + "/{storeLocationId}", storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanGetById() throws Exception {
            when(storeLocationService.getLocationById(storeLocationId)).thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(FLAT_URL + "/{storeLocationId}", storeLocationId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 200 OK for lc-company-store-read")
        void lcCompanyStoreReadCanGetById() throws Exception {
            when(storeLocationService.getLocationById(storeLocationId)).thenReturn(buildStoreLocationResponse());

            mockMvc.perform(get(FLAT_URL + "/{storeLocationId}", storeLocationId))
                    .andExpect(status().isOk());
        }
    }
}
