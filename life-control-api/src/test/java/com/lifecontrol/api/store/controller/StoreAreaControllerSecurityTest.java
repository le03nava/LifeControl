package com.lifecontrol.api.store.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.store.dto.CreateStoreAreaRequest;
import com.lifecontrol.api.store.dto.StoreAreaResponse;
import com.lifecontrol.api.store.dto.UpdateStoreAreaRequest;
import com.lifecontrol.api.store.service.StoreAreaService;
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

@WebMvcTest(StoreAreaController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("StoreAreaController Security — @PreAuthorize method-level authorization")
class StoreAreaControllerSecurityTest {

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
    private StoreAreaService storeAreaService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private final UUID companyId = UUID.randomUUID();
    private final UUID companyCountryId = UUID.randomUUID();
    private final UUID regionId = UUID.randomUUID();
    private final UUID zoneId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID areaId = UUID.randomUUID();

    private static final String BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas";

    private StoreAreaResponse buildAreaResponse() {
        return new StoreAreaResponse(
                areaId, storeId, "A01", "Bodega", "Descripción", 1, true, LocalDateTime.now(), LocalDateTime.now());
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAreas {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanList() throws Exception {
            when(storeAreaService.getAllAreas(companyId, companyCountryId, regionId, zoneId, storeId, false))
                    .thenReturn(List.of(buildAreaResponse()));

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanList() throws Exception {
            when(storeAreaService.getAllAreas(companyId, companyCountryId, regionId, zoneId, storeId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanList() throws Exception {
            when(storeAreaService.getAllAreas(companyId, companyCountryId, regionId, zoneId, storeId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 200 OK for lc-company-region")
        void lcCompanyRegionCanList() throws Exception {
            when(storeAreaService.getAllAreas(companyId, companyCountryId, regionId, zoneId, storeId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanList() throws Exception {
            when(storeAreaService.getAllAreas(companyId, companyCountryId, regionId, zoneId, storeId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanList() throws Exception {
            when(storeAreaService.getAllAreas(companyId, companyCountryId, regionId, zoneId, storeId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 200 OK for lc-company-store-read")
        void lcCompanyStoreReadCanList() throws Exception {
            when(storeAreaService.getAllAreas(companyId, companyCountryId, regionId, zoneId, storeId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{areaId}")
    class GetAreaById {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanGetById() throws Exception {
            when(storeAreaService.getAreaById(companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .thenReturn(buildAreaResponse());

            mockMvc.perform(get(BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanGetById() throws Exception {
            when(storeAreaService.getAreaById(companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .thenReturn(buildAreaResponse());

            mockMvc.perform(get(BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 200 OK for lc-company-store-read")
        void lcCompanyStoreReadCanGetById() throws Exception {
            when(storeAreaService.getAreaById(companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .thenReturn(buildAreaResponse());

            mockMvc.perform(get(BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST " + BASE_URL)
    class PostAreas {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new CreateStoreAreaRequest("A01", "Bodega", null, 1);
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            var request = new CreateStoreAreaRequest("A01", "Bodega", null, 1);
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for read-only role")
        void readOnlyRoleCannotCreate() throws Exception {
            var request = new CreateStoreAreaRequest("A01", "Bodega", null, 1);
            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 Created for lc-admin")
        void lcAdminCanCreate() throws Exception {
            var request = new CreateStoreAreaRequest("A01", "Bodega", null, 1);
            when(storeAreaService.createArea(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            any(CreateStoreAreaRequest.class)))
                    .thenReturn(buildAreaResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 201 Created for lc-company-store")
        void lcCompanyStoreCanCreate() throws Exception {
            var request = new CreateStoreAreaRequest("A01", "Bodega", null, 1);
            when(storeAreaService.createArea(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            any(CreateStoreAreaRequest.class)))
                    .thenReturn(buildAreaResponse());

            mockMvc.perform(post(BASE_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{areaId}")
    class PutAreas {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            var request = new UpdateStoreAreaRequest("A02", "Bodega", null, 2);
            mockMvc.perform(put(BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for read-only role")
        void readOnlyRoleCannotUpdate() throws Exception {
            var request = new UpdateStoreAreaRequest("A02", "Bodega", null, 2);
            mockMvc.perform(put(BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanUpdate() throws Exception {
            var request = new UpdateStoreAreaRequest("A02", "Bodega", null, 2);
            when(storeAreaService.updateArea(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            any(UpdateStoreAreaRequest.class)))
                    .thenReturn(buildAreaResponse());

            mockMvc.perform(put(BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanUpdate() throws Exception {
            var request = new UpdateStoreAreaRequest("A02", "Bodega", null, 2);
            when(storeAreaService.updateArea(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            eq(areaId),
                            any(UpdateStoreAreaRequest.class)))
                    .thenReturn(buildAreaResponse());

            mockMvc.perform(put(BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{areaId}")
    class DeleteAreas {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for read-only role")
        void readOnlyRoleCannotDelete() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 204 No Content for lc-admin")
        void lcAdminCanDelete() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 204 No Content for lc-company-store")
        void lcCompanyStoreCanDelete() throws Exception {
            mockMvc.perform(delete(
                            BASE_URL + "/{areaId}", companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("PATCH " + BASE_URL + "/{areaId}/enable")
    class PatchAreas {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(patch(
                            BASE_URL + "/{areaId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for read-only role")
        void readOnlyRoleCannotEnable() throws Exception {
            mockMvc.perform(patch(
                            BASE_URL + "/{areaId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanEnable() throws Exception {
            when(storeAreaService.enableArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .thenReturn(buildAreaResponse());

            mockMvc.perform(patch(
                            BASE_URL + "/{areaId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanEnable() throws Exception {
            when(storeAreaService.enableArea(companyId, companyCountryId, regionId, zoneId, storeId, areaId))
                    .thenReturn(buildAreaResponse());

            mockMvc.perform(patch(
                            BASE_URL + "/{areaId}/enable",
                            companyId,
                            companyCountryId,
                            regionId,
                            zoneId,
                            storeId,
                            areaId))
                    .andExpect(status().isOk());
        }
    }
}
