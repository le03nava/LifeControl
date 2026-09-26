package com.lifecontrol.api.store.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.store.dto.StoreLocationResponse;
import com.lifecontrol.api.store.exception.StoreLocationNotFoundException;
import com.lifecontrol.api.store.service.StoreLocationService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("StoreLocationFlatController Tests")
class StoreLocationFlatControllerTest {

    private MockMvc mockMvc;

    @Mock
    private StoreLocationService storeLocationService;

    @InjectMocks
    private StoreLocationFlatController storeLocationFlatController;

    private UUID testCompanyId;
    private UUID testCompanyCountryId;
    private UUID testRegionId;
    private UUID testZoneId;
    private UUID testStoreId;
    private UUID testAreaId;
    private UUID testStoreZoneId;
    private UUID testStoreLocationId;
    private StoreLocationResponse testStoreLocationResponse;
    private LocalDateTime now;

    private static final String BASE_URL = "/api/store-locations";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(storeLocationFlatController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        testCompanyId = UUID.randomUUID();
        testCompanyCountryId = UUID.randomUUID();
        testRegionId = UUID.randomUUID();
        testZoneId = UUID.randomUUID();
        testStoreId = UUID.randomUUID();
        testAreaId = UUID.randomUUID();
        testStoreZoneId = UUID.randomUUID();
        testStoreLocationId = UUID.randomUUID();
        now = LocalDateTime.now();

        testStoreLocationResponse = new StoreLocationResponse(
                testStoreLocationId,
                testStoreZoneId,
                testAreaId,
                testStoreId,
                testCompanyId,
                testCompanyCountryId,
                testRegionId,
                testZoneId,
                "L01",
                "Estante",
                "Ubicación de estante",
                1,
                true,
                now,
                now,
                0L);
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{storeLocationId}")
    class GetLocationByIdTests {

        @Test
        @DisplayName("should return 200 with the location and its resolved chain")
        void getLocationById_Success() throws Exception {
            when(storeLocationService.getLocationById(testStoreLocationId)).thenReturn(testStoreLocationResponse);

            mockMvc.perform(get(BASE_URL + "/{storeLocationId}", testStoreLocationId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testStoreLocationId.toString()))
                    .andExpect(jsonPath("$.storeZoneId").value(testStoreZoneId.toString()))
                    .andExpect(jsonPath("$.storeAreaId").value(testAreaId.toString()))
                    .andExpect(jsonPath("$.companyStoreId").value(testStoreId.toString()))
                    .andExpect(jsonPath("$.companyId").value(testCompanyId.toString()))
                    .andExpect(jsonPath("$.companyCountryId").value(testCompanyCountryId.toString()))
                    .andExpect(jsonPath("$.regionId").value(testRegionId.toString()))
                    .andExpect(jsonPath("$.zoneId").value(testZoneId.toString()))
                    .andExpect(jsonPath("$.locationCode").value("L01"))
                    .andExpect(jsonPath("$.locationName").value("Estante"))
                    .andExpect(jsonPath("$.description").value("Ubicación de estante"))
                    .andExpect(jsonPath("$.displayOrder").value(1))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when the location does not exist")
        void getLocationById_NotFound() throws Exception {
            when(storeLocationService.getLocationById(testStoreLocationId))
                    .thenThrow(new StoreLocationNotFoundException(testStoreLocationId));

            mockMvc.perform(get(BASE_URL + "/{storeLocationId}", testStoreLocationId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store location not found with id: " + testStoreLocationId));
        }
    }
}
