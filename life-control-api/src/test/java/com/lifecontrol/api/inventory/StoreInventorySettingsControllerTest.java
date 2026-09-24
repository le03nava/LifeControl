package com.lifecontrol.api.inventory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.inventory.controller.StoreInventorySettingsController;
import com.lifecontrol.api.inventory.controller.StoreLocationByStoreController;
import com.lifecontrol.api.inventory.dto.StoreInventorySettingsRequest;
import com.lifecontrol.api.inventory.dto.StoreInventorySettingsResponse;
import com.lifecontrol.api.inventory.dto.StoreLocationSummaryResponse;
import com.lifecontrol.api.inventory.exception.StoreInventorySettingsNotFoundException;
import com.lifecontrol.api.inventory.exception.StoreLocationNotInStoreException;
import com.lifecontrol.api.inventory.service.StoreInventorySettingsService;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller-level verification of the nested store inventory-settings and store-locations
 * endpoints: their paths, their serialized contract and their error mapping.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Store inventory settings controllers Tests")
class StoreInventorySettingsControllerTest {

    private static final String SETTINGS_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/inventory-settings";
    private static final String LOCATIONS_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/store-locations";

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private StoreInventorySettingsService storeInventorySettingsService;

    @InjectMocks
    private StoreInventorySettingsController settingsController;

    @InjectMocks
    private StoreLocationByStoreController locationsController;

    private UUID companyId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private UUID storeId;
    private UUID receivingLocationId;
    private UUID salesLocationId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(settingsController, locationsController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        companyId = UUID.randomUUID();
        companyCountryId = UUID.randomUUID();
        regionId = UUID.randomUUID();
        zoneId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        receivingLocationId = UUID.randomUUID();
        salesLocationId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("GET " + SETTINGS_URL)
    class GetSettingsTests {

        @Test
        @DisplayName("should return 200 with the store's settings")
        void getSettings_Success() throws Exception {
            when(storeInventorySettingsService.getSettings(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenReturn(new StoreInventorySettingsResponse(storeId, receivingLocationId, salesLocationId, 7L));

            mockMvc.perform(get(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.receivingLocationId").value(receivingLocationId.toString()))
                    .andExpect(jsonPath("$.salesLocationId").value(salesLocationId.toString()))
                    .andExpect(jsonPath("$.version").value(7));
        }

        @Test
        @DisplayName("should return 404 when the store has never been configured")
        void getSettings_NotConfiguredReturns404() throws Exception {
            when(storeInventorySettingsService.getSettings(companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenThrow(new StoreInventorySettingsNotFoundException(storeId));

            mockMvc.perform(get(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isNotFound())
                    .andExpect(
                            jsonPath("$.message").value("Store inventory settings not found for store id: " + storeId));
        }
    }

    @Nested
    @DisplayName("PUT " + SETTINGS_URL)
    class UpsertSettingsTests {

        @Test
        @DisplayName("should return 200 with the saved settings")
        void upsertSettings_Success() throws Exception {
            var request = new StoreInventorySettingsRequest(receivingLocationId, salesLocationId);
            when(storeInventorySettingsService.upsertSettings(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            any(StoreInventorySettingsRequest.class)))
                    .thenReturn(new StoreInventorySettingsResponse(storeId, receivingLocationId, salesLocationId, 7L));

            mockMvc.perform(put(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.receivingLocationId").value(receivingLocationId.toString()))
                    .andExpect(jsonPath("$.salesLocationId").value(salesLocationId.toString()))
                    .andExpect(jsonPath("$.version").value(7));
        }

        @Test
        @DisplayName("should return 400 when a location id is missing")
        void upsertSettings_MissingLocationReturns400() throws Exception {
            mockMvc.perform(put(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"receivingLocationId\":null,\"salesLocationId\":null}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.receivingLocationId").exists())
                    .andExpect(jsonPath("$.errors.salesLocationId").exists());
        }

        @Test
        @DisplayName("should return 404 when a location does not belong to the store")
        void upsertSettings_LocationNotInStoreReturns404() throws Exception {
            var request = new StoreInventorySettingsRequest(receivingLocationId, salesLocationId);
            when(storeInventorySettingsService.upsertSettings(
                            eq(companyId),
                            eq(companyCountryId),
                            eq(regionId),
                            eq(zoneId),
                            eq(storeId),
                            any(StoreInventorySettingsRequest.class)))
                    .thenThrow(new StoreLocationNotInStoreException(receivingLocationId));

            mockMvc.perform(put(SETTINGS_URL, companyId, companyCountryId, regionId, zoneId, storeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message")
                            .value("Store location not found with id: " + receivingLocationId
                                    + " in the requested store"));
        }
    }

    @Nested
    @DisplayName("GET " + LOCATIONS_URL)
    class ListStoreLocationsTests {

        @Test
        @DisplayName("should return 200 with the store's enabled locations")
        void listStoreLocations_Success() throws Exception {
            when(storeInventorySettingsService.listStoreLocations(
                            companyId, companyCountryId, regionId, zoneId, storeId))
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

            mockMvc.perform(get(LOCATIONS_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].id").value(receivingLocationId.toString()))
                    .andExpect(jsonPath("$[0].locationCode").value("L1"))
                    .andExpect(jsonPath("$[0].locationName").value("Receiving shelf"))
                    .andExpect(jsonPath("$[0].zoneCode").value("Z1"))
                    .andExpect(jsonPath("$[0].areaCode").value("A1"));
        }

        @Test
        @DisplayName("should return 404 when the store does not exist")
        void listStoreLocations_UnknownStoreReturns404() throws Exception {
            when(storeInventorySettingsService.listStoreLocations(
                            companyId, companyCountryId, regionId, zoneId, storeId))
                    .thenThrow(new CompanyStoreNotFoundException(storeId));

            mockMvc.perform(get(LOCATIONS_URL, companyId, companyCountryId, regionId, zoneId, storeId))
                    .andExpect(status().isNotFound());
        }
    }
}
