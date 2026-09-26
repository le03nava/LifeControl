package com.lifecontrol.api.store.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.store.dto.CreateStoreZoneRequest;
import com.lifecontrol.api.store.dto.StoreZoneResponse;
import com.lifecontrol.api.store.dto.UpdateStoreZoneRequest;
import com.lifecontrol.api.store.service.StoreZoneService;
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

@WebMvcTest({StoreZoneController.class, StoreZoneFlatController.class})
@Import(GlobalExceptionHandler.class)
@DisplayName("StoreZoneController Security — @PreAuthorize method-level authorization")
class StoreZoneControllerSecurityTest {

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
    private StoreZoneService storeZoneService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID companyCountryId = UUID.randomUUID();
    private final UUID regionId = UUID.randomUUID();
    private final UUID zoneId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID areaId = UUID.randomUUID();
    private final UUID storeZoneId = UUID.randomUUID();

    private static final String BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas/{areaId}/store-zones";

    private StoreZoneResponse buildStoreZoneResponse() {
        return new StoreZoneResponse(
                storeZoneId,
                areaId,
                storeId,
                companyId,
                companyCountryId,
                regionId,
                zoneId,
                "Z01",
                "Pasillo",
                "Descripción",
                1,
                true,
                LocalDateTime.now(),
                LocalDateTime.now(),
                0L);
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetZones {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanList() throws Exception {
            when(storeZoneService.getAllZones(companyId, companyCountryId, regionId, zoneId, storeId, areaId, false))
                    .thenReturn(List.of(buildStoreZoneResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanList() throws Exception {
            when(storeZoneService.getAllZones(companyId, companyCountryId, regionId, zoneId, storeId, areaId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanList() throws Exception {
            when(storeZoneService.getAllZones(companyId, companyCountryId, regionId, zoneId, storeId, areaId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 200 OK for lc-company-region")
        void lcCompanyRegionCanList() throws Exception {
            when(storeZoneService.getAllZones(companyId, companyCountryId, regionId, zoneId, storeId, areaId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanList() throws Exception {
            when(storeZoneService.getAllZones(companyId, companyCountryId, regionId, zoneId, storeId, areaId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanList() throws Exception {
            when(storeZoneService.getAllZones(companyId, companyCountryId, regionId, zoneId, storeId, areaId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 200 OK for lc-company-store-read")
        void lcCompanyStoreReadCanList() throws Exception {
            when(storeZoneService.getAllZones(companyId, companyCountryId, regionId, zoneId, storeId, areaId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{storeZoneId}")
    class GetZoneById {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(
                            BASE_URL + "/{storeZoneId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(
                            BASE_URL + "/{storeZoneId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanGetById() throws Exception {
            when(storeZoneService.getZoneById(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .thenReturn(buildStoreZoneResponse());

            mockMvc.perform(get(
                            BASE_URL + "/{storeZoneId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanGetById() throws Exception {
            when(storeZoneService.getZoneById(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .thenReturn(buildStoreZoneResponse());

            mockMvc.perform(get(
                            BASE_URL + "/{storeZoneId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 200 OK for lc-company-store-read")
        void lcCompanyStoreReadCanGetById() throws Exception {
            when(storeZoneService.getZoneById(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .thenReturn(buildStoreZoneResponse());

            mockMvc.perform(get(
                            BASE_URL + "/{storeZoneId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST " + BASE_URL)
    class PostZones {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new CreateStoreZoneRequest("Z01", "Pasillo", null, 1);
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            var request = new CreateStoreZoneRequest("Z01", "Pasillo", null, 1);
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for read-only role")
        void readOnlyRoleCannotCreate() throws Exception {
            var request = new CreateStoreZoneRequest("Z01", "Pasillo", null, 1);
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for lc-admin")
        void lcAdminCanCreate() throws Exception {
            var request = new CreateStoreZoneRequest("Z01", "Pasillo", null, 1);
            when(storeZoneService.createZone(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            any(CreateStoreZoneRequest.class)))
                    .thenReturn(buildStoreZoneResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 201 Created for lc-company-store")
        void lcCompanyStoreCanCreate() throws Exception {
            var request = new CreateStoreZoneRequest("Z01", "Pasillo", null, 1);
            when(storeZoneService.createZone(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            any(CreateStoreZoneRequest.class)))
                    .thenReturn(buildStoreZoneResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{storeZoneId}")
    class PutZones {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new UpdateStoreZoneRequest("Z02", "Pasillo", null, 2);
            mockMvc.perform(put(
                                    BASE_URL + "/{storeZoneId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for read-only role")
        void readOnlyRoleCannotUpdate() throws Exception {
            var request = new UpdateStoreZoneRequest("Z02", "Pasillo", null, 2);
            mockMvc.perform(put(
                                    BASE_URL + "/{storeZoneId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanUpdate() throws Exception {
            var request = new UpdateStoreZoneRequest("Z02", "Pasillo", null, 2);
            when(storeZoneService.updateZone(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            any(UpdateStoreZoneRequest.class)))
                    .thenReturn(buildStoreZoneResponse());

            mockMvc.perform(put(
                                    BASE_URL + "/{storeZoneId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanUpdate() throws Exception {
            var request = new UpdateStoreZoneRequest("Z02", "Pasillo", null, 2);
            when(storeZoneService.updateZone(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            eq(storeZoneId),
                            any(UpdateStoreZoneRequest.class)))
                    .thenReturn(buildStoreZoneResponse());

            mockMvc.perform(put(
                                    BASE_URL + "/{storeZoneId}",
                                    companyId,
                                    companyCountryId,
                                    regionId,
                                    zoneId,
                                    storeId,
                                    areaId,
                                    storeZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{storeZoneId}")
    class DeleteZones {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{storeZoneId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for read-only role")
        void readOnlyRoleCannotDelete() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{storeZoneId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for lc-admin")
        void lcAdminCanDelete() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{storeZoneId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 204 No Content for lc-company-store")
        void lcCompanyStoreCanDelete() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{storeZoneId}",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("PATCH " + BASE_URL + "/{storeZoneId}/enable")
    class PatchZones {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(patch(
                            BASE_URL + "/{storeZoneId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for read-only role")
        void readOnlyRoleCannotEnable() throws Exception {
            mockMvc.perform(patch(
                            BASE_URL + "/{storeZoneId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanEnable() throws Exception {
            when(storeZoneService.enableZone(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .thenReturn(buildStoreZoneResponse());

            mockMvc.perform(patch(
                            BASE_URL + "/{storeZoneId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanEnable() throws Exception {
            when(storeZoneService.enableZone(
                            companyId, companyCountryId, regionId, zoneId, storeId, areaId, storeZoneId))
                    .thenReturn(buildStoreZoneResponse());

            mockMvc.perform(patch(
                            BASE_URL + "/{storeZoneId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId,
                            storeZoneId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /api/store-zones/{storeZoneId} (flat)")
    class GetZoneByIdFlat {

        private static final String FLAT_URL = "/api/store-zones";

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no matching role")
        void userWithNoMatchingRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(FLAT_URL + "/{storeZoneId}", storeZoneId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(FLAT_URL + "/{storeZoneId}", storeZoneId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanGetById() throws Exception {
            when(storeZoneService.getZoneById(storeZoneId)).thenReturn(buildStoreZoneResponse());

            mockMvc.perform(get(FLAT_URL + "/{storeZoneId}", storeZoneId)).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 200 OK for lc-company-store-read")
        void lcCompanyStoreReadCanGetById() throws Exception {
            when(storeZoneService.getZoneById(storeZoneId)).thenReturn(buildStoreZoneResponse());

            mockMvc.perform(get(FLAT_URL + "/{storeZoneId}", storeZoneId)).andExpect(status().isOk());
        }
    }
}
