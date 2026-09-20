package com.lifecontrol.api.store.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.inventory.controller.StoreInventorySettingsController;
import com.lifecontrol.api.inventory.dto.StoreInventorySettingsRequest;
import com.lifecontrol.api.inventory.service.StoreInventorySettingsService;
import com.lifecontrol.api.store.dto.CreateCompanyStoreRequest;
import com.lifecontrol.api.store.dto.CreateStoreLocationRequest;
import com.lifecontrol.api.store.service.CompanyStoreService;
import com.lifecontrol.api.store.service.StoreLocationService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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

/**
 * Durable guard for the latent privilege-escalation path of {@code lc-receiving}.
 *
 * <p>{@code CompanyStoreService} encodes "store-scoped" as literal role names instead of resolving
 * the scope from {@link com.lifecontrol.api.common.security.ScopeLevel}, so adding
 * {@code lc-receiving} to a store-administration endpoint's {@code @PreAuthorize} would let a
 * reception-only principal read an entire zone's stores and even create stores. These slice tests
 * pin the intended denial at every store-administration entry point so that edit cannot land
 * unnoticed.</p>
 */
@WebMvcTest({CompanyStoreController.class, StoreLocationController.class, StoreInventorySettingsController.class})
@Import(GlobalExceptionHandler.class)
@DisplayName("lc-receiving-only principals are denied on store-administration endpoints")
class ReceivingRoleSecurityGuardTest {

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
    private CompanyStoreService companyStoreService;

    @MockitoBean
    private StoreLocationService storeLocationService;

    @MockitoBean
    private StoreInventorySettingsService storeInventorySettingsService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private static final String STORES_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores";
    private static final String STORE_LOCATIONS_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas/{areaId}/store-zones/{storeZoneId}/store-locations";
    private static final String INVENTORY_SETTINGS_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/inventory-settings";

    private final UUID companyId = UUID.randomUUID();
    private final UUID companyCountryId = UUID.randomUUID();
    private final UUID regionId = UUID.randomUUID();
    private final UUID zoneId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID areaId = UUID.randomUUID();
    private final UUID storeZoneId = UUID.randomUUID();

    @Test
    @WithMockUser(roles = {"lc-receiving"})
    @DisplayName("CompanyStoreController list is denied for lc-receiving")
    void receivingCannotListStores() throws Exception {
        mockMvc.perform(get(STORES_URL, companyId, companyCountryId, regionId, zoneId))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"lc-receiving"})
    @DisplayName("CompanyStoreController create is denied for lc-receiving")
    void receivingCannotCreateStore() throws Exception {
        var request = new CreateCompanyStoreRequest("Nueva Tienda", "nueva@test.com", "555-0002", null);
        mockMvc.perform(post(STORES_URL, companyId, companyCountryId, regionId, zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"lc-receiving"})
    @DisplayName("StoreLocationController create is denied for lc-receiving")
    void receivingCannotCreateStoreLocation() throws Exception {
        var request = new CreateStoreLocationRequest("L01", "Estante", null, 1);
        mockMvc.perform(post(
                                STORE_LOCATIONS_URL,
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
    @WithMockUser(roles = {"lc-receiving"})
    @DisplayName("StoreInventorySettingsController PUT is denied for lc-receiving")
    void receivingCannotUpsertInventorySettings() throws Exception {
        var request = new StoreInventorySettingsRequest(UUID.randomUUID(), UUID.randomUUID());
        mockMvc.perform(put(INVENTORY_SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }
}
