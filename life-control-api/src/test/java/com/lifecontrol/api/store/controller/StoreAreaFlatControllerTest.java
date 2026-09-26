package com.lifecontrol.api.store.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.store.dto.StoreAreaResponse;
import com.lifecontrol.api.store.exception.StoreAreaNotFoundException;
import com.lifecontrol.api.store.service.StoreAreaService;
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
@DisplayName("StoreAreaFlatController Tests")
class StoreAreaFlatControllerTest {

    private MockMvc mockMvc;

    @Mock
    private StoreAreaService storeAreaService;

    @InjectMocks
    private StoreAreaFlatController storeAreaFlatController;

    private UUID testCompanyId;
    private UUID testCompanyCountryId;
    private UUID testRegionId;
    private UUID testZoneId;
    private UUID testStoreId;
    private UUID testAreaId;
    private StoreAreaResponse testAreaResponse;
    private LocalDateTime now;

    private static final String BASE_URL = "/api/store-areas";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(storeAreaFlatController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        testCompanyId = UUID.randomUUID();
        testCompanyCountryId = UUID.randomUUID();
        testRegionId = UUID.randomUUID();
        testZoneId = UUID.randomUUID();
        testStoreId = UUID.randomUUID();
        testAreaId = UUID.randomUUID();
        now = LocalDateTime.now();

        testAreaResponse = new StoreAreaResponse(
                testAreaId,
                testStoreId,
                testCompanyId,
                testCompanyCountryId,
                testRegionId,
                testZoneId,
                "A01",
                "Bodega",
                "Área de almacenamiento",
                1,
                true,
                now,
                now,
                0L);
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{areaId}")
    class GetAreaByIdTests {

        @Test
        @DisplayName("should return 200 with the area and its resolved chain")
        void getAreaById_Success() throws Exception {
            when(storeAreaService.getAreaById(testAreaId)).thenReturn(testAreaResponse);

            mockMvc.perform(get(BASE_URL + "/{areaId}", testAreaId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testAreaId.toString()))
                    .andExpect(jsonPath("$.companyStoreId").value(testStoreId.toString()))
                    .andExpect(jsonPath("$.companyId").value(testCompanyId.toString()))
                    .andExpect(jsonPath("$.companyCountryId").value(testCompanyCountryId.toString()))
                    .andExpect(jsonPath("$.regionId").value(testRegionId.toString()))
                    .andExpect(jsonPath("$.zoneId").value(testZoneId.toString()))
                    .andExpect(jsonPath("$.areaCode").value("A01"))
                    .andExpect(jsonPath("$.areaName").value("Bodega"))
                    .andExpect(jsonPath("$.description").value("Área de almacenamiento"))
                    .andExpect(jsonPath("$.displayOrder").value(1))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when the area does not exist")
        void getAreaById_NotFound() throws Exception {
            when(storeAreaService.getAreaById(testAreaId)).thenThrow(new StoreAreaNotFoundException(testAreaId));

            mockMvc.perform(get(BASE_URL + "/{areaId}", testAreaId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store area not found with id: " + testAreaId));
        }
    }
}
