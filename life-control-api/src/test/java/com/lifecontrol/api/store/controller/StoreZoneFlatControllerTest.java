package com.lifecontrol.api.store.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.store.dto.StoreZoneResponse;
import com.lifecontrol.api.store.exception.StoreZoneNotFoundException;
import com.lifecontrol.api.store.service.StoreZoneService;
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
@DisplayName("StoreZoneFlatController Tests")
class StoreZoneFlatControllerTest {

    private MockMvc mockMvc;

    @Mock
    private StoreZoneService storeZoneService;

    @InjectMocks
    private StoreZoneFlatController storeZoneFlatController;

    private UUID testCompanyId;
    private UUID testCompanyCountryId;
    private UUID testRegionId;
    private UUID testZoneId;
    private UUID testStoreId;
    private UUID testAreaId;
    private UUID testStoreZoneId;
    private StoreZoneResponse testStoreZoneResponse;
    private LocalDateTime now;

    private static final String BASE_URL = "/api/store-zones";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(storeZoneFlatController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        testCompanyId = UUID.randomUUID();
        testCompanyCountryId = UUID.randomUUID();
        testRegionId = UUID.randomUUID();
        testZoneId = UUID.randomUUID();
        testStoreId = UUID.randomUUID();
        testAreaId = UUID.randomUUID();
        testStoreZoneId = UUID.randomUUID();
        now = LocalDateTime.now();

        testStoreZoneResponse = new StoreZoneResponse(
                testStoreZoneId,
                testAreaId,
                testStoreId,
                testCompanyId,
                testCompanyCountryId,
                testRegionId,
                testZoneId,
                "Z01",
                "Pasillo",
                "Zona de pasillo",
                1,
                true,
                now,
                now);
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{storeZoneId}")
    class GetZoneByIdTests {

        @Test
        @DisplayName("should return 200 with the zone and its resolved chain")
        void getZoneById_Success() throws Exception {
            when(storeZoneService.getZoneById(testStoreZoneId)).thenReturn(testStoreZoneResponse);

            mockMvc.perform(get(BASE_URL + "/{storeZoneId}", testStoreZoneId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testStoreZoneId.toString()))
                    .andExpect(jsonPath("$.storeAreaId").value(testAreaId.toString()))
                    .andExpect(jsonPath("$.companyStoreId").value(testStoreId.toString()))
                    .andExpect(jsonPath("$.companyId").value(testCompanyId.toString()))
                    .andExpect(jsonPath("$.companyCountryId").value(testCompanyCountryId.toString()))
                    .andExpect(jsonPath("$.regionId").value(testRegionId.toString()))
                    .andExpect(jsonPath("$.zoneId").value(testZoneId.toString()))
                    .andExpect(jsonPath("$.zoneCode").value("Z01"))
                    .andExpect(jsonPath("$.zoneName").value("Pasillo"))
                    .andExpect(jsonPath("$.description").value("Zona de pasillo"))
                    .andExpect(jsonPath("$.displayOrder").value(1))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when the zone does not exist")
        void getZoneById_NotFound() throws Exception {
            when(storeZoneService.getZoneById(testStoreZoneId))
                    .thenThrow(new StoreZoneNotFoundException(testStoreZoneId));

            mockMvc.perform(get(BASE_URL + "/{storeZoneId}", testStoreZoneId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store zone not found with id: " + testStoreZoneId));
        }
    }
}
