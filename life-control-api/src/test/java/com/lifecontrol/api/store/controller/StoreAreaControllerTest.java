package com.lifecontrol.api.store.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.store.dto.CreateStoreAreaRequest;
import com.lifecontrol.api.store.dto.StoreAreaResponse;
import com.lifecontrol.api.store.dto.UpdateStoreAreaRequest;
import com.lifecontrol.api.store.exception.DuplicateStoreAreaException;
import com.lifecontrol.api.store.exception.StoreAreaNotFoundException;
import com.lifecontrol.api.store.service.StoreAreaService;
import java.time.LocalDateTime;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("StoreAreaController Tests")
class StoreAreaControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private StoreAreaService storeAreaService;

    @InjectMocks
    private StoreAreaController storeAreaController;

    private UUID testCompanyId;
    private UUID testCompanyCountryId;
    private UUID testRegionId;
    private UUID testZoneId;
    private UUID testStoreId;
    private UUID testAreaId;
    private StoreAreaResponse testAreaResponse;
    private LocalDateTime now;

    private static final String BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(storeAreaController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

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
                now);
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAllAreasTests {

        @Test
        @DisplayName("should return 200 with the list of areas")
        void getAllAreas_Success() throws Exception {
            when(storeAreaService.getAllAreas(
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId, false))
                    .thenReturn(List.of(testAreaResponse));

            mockMvc.perform(get(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].areaCode").value("A01"))
                    .andExpect(jsonPath("$[0].areaName").value("Bodega"))
                    .andExpect(jsonPath("$[0].companyStoreId").value(testStoreId.toString()))
                    .andExpect(jsonPath("$[0].enabled").value(true));
        }

        @Test
        @DisplayName("should forward includeDisabled=true to the service")
        void getAllAreas_WithIncludeDisabled() throws Exception {
            when(storeAreaService.getAllAreas(
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId, true))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId)
                            .param("includeDisabled", "true"))
                    .andExpect(status().isOk());

            verify(storeAreaService)
                    .getAllAreas(testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId, true);
        }

        @Test
        @DisplayName("should default includeDisabled to false")
        void getAllAreas_DefaultsToActiveOnly() throws Exception {
            when(storeAreaService.getAllAreas(
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId, false))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId))
                    .andExpect(status().isOk());

            verify(storeAreaService)
                    .getAllAreas(testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId, false);
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{areaId}")
    class GetAreaByIdTests {

        @Test
        @DisplayName("should return 200 with the area")
        void getAreaById_Success() throws Exception {
            when(storeAreaService.getAreaById(
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId, testAreaId))
                    .thenReturn(testAreaResponse);

            mockMvc.perform(get(
                            BASE_URL + "/{areaId}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testAreaId.toString()))
                    .andExpect(jsonPath("$.areaCode").value("A01"));
        }

        @Test
        @DisplayName("should return 404 when the area does not exist")
        void getAreaById_NotFound() throws Exception {
            when(storeAreaService.getAreaById(
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId, testAreaId))
                    .thenThrow(new StoreAreaNotFoundException(testAreaId));

            mockMvc.perform(get(
                            BASE_URL + "/{areaId}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store area not found with id: " + testAreaId));
        }
    }

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateAreaTests {

        @Test
        @DisplayName("should return 201 with the created area")
        void createArea_Success() throws Exception {
            var request = new CreateStoreAreaRequest("A01", "Bodega", "Área de almacenamiento", 1);
            when(storeAreaService.createArea(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            any(CreateStoreAreaRequest.class)))
                    .thenReturn(testAreaResponse);

            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.areaCode").value("A01"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 400 when areaCode is blank")
        void createArea_ValidationError() throws Exception {
            var invalidRequest = new CreateStoreAreaRequest("", "Bodega", null, null);

            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.areaCode").exists());
        }

        @Test
        @DisplayName("should return 400 when areaCode exceeds 10 characters")
        void createArea_AreaCodeTooLong() throws Exception {
            var invalidRequest = new CreateStoreAreaRequest("A0123456789", "Bodega", null, null);

            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.areaCode").exists());
        }

        @Test
        @DisplayName("should return 409 when the area code already exists in the store")
        void createArea_Duplicate() throws Exception {
            var request = new CreateStoreAreaRequest("A01", "Bodega", null, null);
            when(storeAreaService.createArea(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            any(CreateStoreAreaRequest.class)))
                    .thenThrow(
                            new DuplicateStoreAreaException("Store area with code 'A01' already exists in this store"));

            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Store area with code 'A01' already exists in this store"));
        }
    }

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{areaId}")
    class UpdateAreaTests {

        @Test
        @DisplayName("should return 200 with the updated area")
        void updateArea_Success() throws Exception {
            var request = new UpdateStoreAreaRequest("A03", "Piso de venta", "Actualizada", 3);
            var updatedResponse = new StoreAreaResponse(
                    testAreaId,
                    testStoreId,
                    testCompanyId,
                    testCompanyCountryId,
                    testRegionId,
                    testZoneId,
                    "A03",
                    "Piso de venta",
                    "Actualizada",
                    3,
                    true,
                    now,
                    now);
            when(storeAreaService.updateArea(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            any(UpdateStoreAreaRequest.class)))
                    .thenReturn(updatedResponse);

            mockMvc.perform(put(
                                    BASE_URL + "/{areaId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.areaCode").value("A03"))
                    .andExpect(jsonPath("$.areaName").value("Piso de venta"))
                    .andExpect(jsonPath("$.displayOrder").value(3));
        }

        @Test
        @DisplayName("should return 400 when a provided areaName is blank")
        void updateArea_BlankName() throws Exception {
            var invalidRequest = new UpdateStoreAreaRequest(null, "", null, null);

            mockMvc.perform(put(
                                    BASE_URL + "/{areaId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.areaName").exists());
        }

        @Test
        @DisplayName("should return 404 when the area does not exist")
        void updateArea_NotFound() throws Exception {
            var request = new UpdateStoreAreaRequest("A03", null, null, null);
            when(storeAreaService.updateArea(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            any(UpdateStoreAreaRequest.class)))
                    .thenThrow(new StoreAreaNotFoundException(testAreaId));

            mockMvc.perform(put(
                                    BASE_URL + "/{areaId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store area not found with id: " + testAreaId));
        }

        @Test
        @DisplayName("should return 409 when the new area code collides inside the store")
        void updateArea_Duplicate() throws Exception {
            var request = new UpdateStoreAreaRequest("A03", null, null, null);
            when(storeAreaService.updateArea(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            any(UpdateStoreAreaRequest.class)))
                    .thenThrow(
                            new DuplicateStoreAreaException("Store area with code 'A03' already exists in this store"));

            mockMvc.perform(put(
                                    BASE_URL + "/{areaId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{areaId}")
    class DeleteAreaTests {

        @Test
        @DisplayName("should return 204 on soft delete")
        void deleteArea_Success() throws Exception {
            doNothing()
                    .when(storeAreaService)
                    .deleteArea(testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId, testAreaId);

            mockMvc.perform(delete(
                            BASE_URL + "/{areaId}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId))
                    .andExpect(status().isNoContent());

            verify(storeAreaService)
                    .deleteArea(testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId, testAreaId);
        }

        @Test
        @DisplayName("should return 404 when the area does not exist")
        void deleteArea_NotFound() throws Exception {
            doThrow(new StoreAreaNotFoundException(testAreaId))
                    .when(storeAreaService)
                    .deleteArea(testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId, testAreaId);

            mockMvc.perform(delete(
                            BASE_URL + "/{areaId}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH " + BASE_URL + "/{areaId}/enable")
    class EnableAreaTests {

        @Test
        @DisplayName("should return 200 with the re-enabled area")
        void enableArea_Success() throws Exception {
            when(storeAreaService.enableArea(
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId, testAreaId))
                    .thenReturn(testAreaResponse);

            mockMvc.perform(patch(
                            BASE_URL + "/{areaId}/enable",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when the area does not exist")
        void enableArea_NotFound() throws Exception {
            when(storeAreaService.enableArea(
                            testCompanyId, testCompanyCountryId, testRegionId, testZoneId, testStoreId, testAreaId))
                    .thenThrow(new StoreAreaNotFoundException(testAreaId));

            mockMvc.perform(patch(
                            BASE_URL + "/{areaId}/enable",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId))
                    .andExpect(status().isNotFound());
        }
    }
}
