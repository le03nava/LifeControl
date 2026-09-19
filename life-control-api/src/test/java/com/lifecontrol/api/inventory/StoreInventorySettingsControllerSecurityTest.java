package com.lifecontrol.api.inventory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.inventory.controller.StoreInventorySettingsController;
import com.lifecontrol.api.inventory.controller.StoreLocationByStoreController;
import com.lifecontrol.api.inventory.dto.StoreInventorySettingsRequest;
import com.lifecontrol.api.inventory.dto.StoreInventorySettingsResponse;
import com.lifecontrol.api.inventory.dto.StoreLocationSummaryResponse;
import com.lifecontrol.api.inventory.service.StoreInventorySettingsService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
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

/**
 * Pins the {@code @PreAuthorize} role sets of both nested endpoints: read access adds
 * {@code lc-company-store-read}, write access does not.
 */
@WebMvcTest({StoreInventorySettingsController.class, StoreLocationByStoreController.class})
@Import(GlobalExceptionHandler.class)
@DisplayName("Store inventory settings Security — @PreAuthorize method-level authorization")
class StoreInventorySettingsControllerSecurityTest {

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @org.springframework.context.annotation.Bean
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
    private StoreInventorySettingsService storeInventorySettingsService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private static final String SETTINGS_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/inventory-settings";
    private static final String LOCATIONS_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/store-locations";

    private final UUID companyId = UUID.randomUUID();
    private final UUID companyCountryId = UUID.randomUUID();
    private final UUID regionId = UUID.randomUUID();
    private final UUID zoneId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID receivingLocationId = UUID.randomUUID();
    private final UUID salesLocationId = UUID.randomUUID();

    private StoreInventorySettingsRequest request;
    private StoreInventorySettingsResponse response;

    @BeforeEach
    void setUp() {
        request = new StoreInventorySettingsRequest(receivingLocationId, salesLocationId);
        response = new StoreInventorySettingsResponse(storeId, receivingLocationId, salesLocationId);

        when(storeInventorySettingsService.getSettings(companyId, companyCountryId, regionId, zoneId, storeId))
                .thenReturn(response);
        when(storeInventorySettingsService.upsertSettings(
                        eq(companyId),
                        eq(companyCountryId),
                        eq(regionId),
                        eq(zoneId),
                        eq(storeId),
                        any(StoreInventorySettingsRequest.class)))
                .thenReturn(response);
        when(storeInventorySettingsService.listStoreLocations(companyId, companyCountryId, regionId, zoneId, storeId))
                .thenReturn(List.of(new StoreLocationSummaryResponse(
                        receivingLocationId,
                        "L1",
                        "Receiving shelf",
                        UUID.randomUUID(),
                        "Z1",
                        "Aisle",
                        UUID.randomUUID(),
                        "A1",
                        "Warehouse")));
    }

    @Nested
    @DisplayName("GET " + SETTINGS_URL)
    class GetSettings {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 200 OK for the read-only store role")
        void readOnlyStoreRoleCanRead() throws Exception {
            mockMvc.perform(get(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanRead() throws Exception {
            mockMvc.perform(get(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanRead() throws Exception {
            mockMvc.perform(get(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("PUT " + SETTINGS_URL)
    class PutSettings {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(put(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(put(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 403 for the read-only store role")
        void readOnlyStoreRoleCannotWrite() throws Exception {
            mockMvc.perform(put(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 OK for lc-admin")
        void lcAdminCanWrite() throws Exception {
            mockMvc.perform(put(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company"})
        @DisplayName("returns 200 OK for lc-company")
        void lcCompanyCanWrite() throws Exception {
            mockMvc.perform(put(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-country"})
        @DisplayName("returns 200 OK for lc-company-country")
        void lcCompanyCountryCanWrite() throws Exception {
            mockMvc.perform(put(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-region"})
        @DisplayName("returns 200 OK for lc-company-region")
        void lcCompanyRegionCanWrite() throws Exception {
            mockMvc.perform(put(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-zone"})
        @DisplayName("returns 200 OK for lc-company-zone")
        void lcCompanyZoneCanWrite() throws Exception {
            mockMvc.perform(put(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store"})
        @DisplayName("returns 200 OK for lc-company-store")
        void lcCompanyStoreCanWrite() throws Exception {
            mockMvc.perform(put(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET " + LOCATIONS_URL)
    class GetStoreLocations {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void userWithNoRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(LOCATIONS_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for user with wrong role")
        void userWithWrongRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(LOCATIONS_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-company-store-read"})
        @DisplayName("returns 200 OK for the read-only store role")
        void readOnlyStoreRoleCanRead() throws Exception {
            mockMvc.perform(get(LOCATIONS_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk());
        }
    }
}
