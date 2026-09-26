package com.lifecontrol.api.store.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.exception.VersionPreconditionException;
import com.lifecontrol.api.store.dto.CreateStoreZoneRequest;
import com.lifecontrol.api.store.dto.StoreZoneResponse;
import com.lifecontrol.api.store.dto.UpdateStoreZoneRequest;
import com.lifecontrol.api.store.exception.DisabledParentException;
import com.lifecontrol.api.store.exception.DuplicateStoreZoneException;
import com.lifecontrol.api.store.exception.StoreZoneNotFoundException;
import com.lifecontrol.api.store.service.StoreZoneService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("StoreZoneController Tests")
class StoreZoneControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private StoreZoneService storeZoneService;

    @InjectMocks
    private StoreZoneController storeZoneController;

    private UUID testCompanyId;
    private UUID testCompanyCountryId;
    private UUID testRegionId;
    private UUID testZoneId;
    private UUID testStoreId;
    private UUID testAreaId;
    private UUID testStoreZoneId;
    private StoreZoneResponse testStoreZoneResponse;
    private LocalDateTime now;

    private static final String BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas/{areaId}/store-zones";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(storeZoneController)
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
                now,
                0L);
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAllZonesTests {

        @Test
        @DisplayName("should return 200 with the list of zones")
        void getAllZones_Success() throws Exception {
            when(storeZoneService.getAllZones(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            false))
                    .thenReturn(List.of(testStoreZoneResponse));

            mockMvc.perform(get(
                            BASE_URL,
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].zoneCode").value("Z01"))
                    .andExpect(jsonPath("$[0].zoneName").value("Pasillo"))
                    .andExpect(jsonPath("$[0].storeAreaId").value(testAreaId.toString()))
                    .andExpect(jsonPath("$[0].flag").doesNotExist())
                    .andExpect(jsonPath("$[0].enabled").value(true));
        }

        @Test
        @DisplayName("should forward includeDisabled=true to the service")
        void getAllZones_WithIncludeDisabled() throws Exception {
            when(storeZoneService.getAllZones(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            true))
                    .thenReturn(List.of());

            mockMvc.perform(get(
                                    BASE_URL,
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId)
                            .param("includeDisabled", "true"))
                    .andExpect(status().isOk());

            verify(storeZoneService)
                    .getAllZones(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            true);
        }

        @Test
        @DisplayName("should default includeDisabled to false")
        void getAllZones_DefaultsToActiveOnly() throws Exception {
            when(storeZoneService.getAllZones(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            false))
                    .thenReturn(List.of());

            mockMvc.perform(get(
                            BASE_URL,
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId))
                    .andExpect(status().isOk());

            verify(storeZoneService)
                    .getAllZones(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            false);
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{storeZoneId}")
    class GetZoneByIdTests {

        @Test
        @DisplayName("should return 200 with the zone")
        void getZoneById_Success() throws Exception {
            when(storeZoneService.getZoneById(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId))
                    .thenReturn(testStoreZoneResponse);

            mockMvc.perform(get(
                            BASE_URL + "/{storeZoneId}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testStoreZoneId.toString()))
                    .andExpect(jsonPath("$.zoneCode").value("Z01"));
        }

        @Test
        @DisplayName("should return 404 when the zone does not exist")
        void getZoneById_NotFound() throws Exception {
            when(storeZoneService.getZoneById(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId))
                    .thenThrow(new StoreZoneNotFoundException(testStoreZoneId));

            mockMvc.perform(get(
                            BASE_URL + "/{storeZoneId}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store zone not found with id: " + testStoreZoneId));
        }
    }

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateZoneTests {

        @Test
        @DisplayName("should return 201 with the created zone")
        void createZone_Success() throws Exception {
            var request = new CreateStoreZoneRequest("Z01", "Pasillo", "Zona de pasillo", 1);
            when(storeZoneService.createZone(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            any(CreateStoreZoneRequest.class)))
                    .thenReturn(testStoreZoneResponse);

            mockMvc.perform(post(
                                    BASE_URL,
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.zoneCode").value("Z01"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 400 when zoneCode is blank")
        void createZone_ValidationError() throws Exception {
            var invalidRequest = new CreateStoreZoneRequest("", "Pasillo", null, null);

            mockMvc.perform(post(
                                    BASE_URL,
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.zoneCode").exists());
        }

        @Test
        @DisplayName("should return 400 when zoneCode exceeds 10 characters")
        void createZone_ZoneCodeTooLong() throws Exception {
            var invalidRequest = new CreateStoreZoneRequest("Z0123456789", "Pasillo", null, null);

            mockMvc.perform(post(
                                    BASE_URL,
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.zoneCode").exists());
        }

        @Test
        @DisplayName("should return 400 when displayOrder is negative")
        void createZone_NegativeDisplayOrderReturns400() throws Exception {
            var invalidRequest = new CreateStoreZoneRequest("Z01", "Pasillo", null, -1);

            mockMvc.perform(post(
                                    BASE_URL,
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.displayOrder").exists());

            verifyNoInteractions(storeZoneService);
        }

        @Test
        @DisplayName("should accept displayOrder = 0")
        void createZone_ZeroDisplayOrderIsAccepted() throws Exception {
            var request = new CreateStoreZoneRequest("Z01", "Pasillo", null, 0);
            when(storeZoneService.createZone(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            any(CreateStoreZoneRequest.class)))
                    .thenReturn(testStoreZoneResponse);

            mockMvc.perform(post(
                                    BASE_URL,
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("should return 409 when the zone code already exists in the area")
        void createZone_Duplicate() throws Exception {
            var request = new CreateStoreZoneRequest("Z01", "Pasillo", null, null);
            when(storeZoneService.createZone(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            any(CreateStoreZoneRequest.class)))
                    .thenThrow(
                            new DuplicateStoreZoneException("Store zone with code 'Z01' already exists in this area"));

            mockMvc.perform(post(
                                    BASE_URL,
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Store zone with code 'Z01' already exists in this area"));
        }

        @Test
        @DisplayName("should return 409 when the parent area is disabled")
        void createZone_DisabledParentArea_Returns409() throws Exception {
            var request = new CreateStoreZoneRequest("Z01", "Pasillo", null, null);
            when(storeZoneService.createZone(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            any(CreateStoreZoneRequest.class)))
                    .thenThrow(new DisabledParentException(
                            "Cannot create a store zone: store area with id " + testAreaId + " is disabled"));

            mockMvc.perform(post(
                                    BASE_URL,
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message")
                            .value("Cannot create a store zone: store area with id " + testAreaId + " is disabled"));
        }

        @Test
        @DisplayName("should return 409 when a concurrent duplicate surfaces as DataIntegrityViolationException")
        void createZone_DataIntegrityViolation_Returns409() throws Exception {
            var request = new CreateStoreZoneRequest("Z01", "Pasillo", null, null);
            when(storeZoneService.createZone(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            any(CreateStoreZoneRequest.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

            mockMvc.perform(post(
                                    BASE_URL,
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
    @DisplayName("PUT " + BASE_URL + "/{storeZoneId}")
    class UpdateZoneTests {

        @Test
        @DisplayName("should return 200 with the updated zone")
        void updateZone_Success() throws Exception {
            var request = new UpdateStoreZoneRequest("Z03", "Estante", "Actualizada", 3);
            var updatedResponse = new StoreZoneResponse(
                    testStoreZoneId,
                    testAreaId,
                    testStoreId,
                    testCompanyId,
                    testCompanyCountryId,
                    testRegionId,
                    testZoneId,
                    "Z03",
                    "Estante",
                    "Actualizada",
                    3,
                    true,
                    now,
                    now,
                    0L);
            when(storeZoneService.updateZone(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            any(UpdateStoreZoneRequest.class)))
                    .thenReturn(updatedResponse);

            mockMvc.perform(put(
                                    BASE_URL + "/{storeZoneId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.zoneCode").value("Z03"))
                    .andExpect(jsonPath("$.zoneName").value("Estante"))
                    .andExpect(jsonPath("$.displayOrder").value(3));
        }

        @Test
        @DisplayName("should return 400 when a provided zoneName is blank")
        void updateZone_BlankName() throws Exception {
            var invalidRequest = new UpdateStoreZoneRequest(null, "", null, null);

            mockMvc.perform(put(
                                    BASE_URL + "/{storeZoneId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.zoneName").exists());
        }

        @Test
        @DisplayName("should return 400 when displayOrder is negative")
        void updateZone_NegativeDisplayOrderReturns400() throws Exception {
            var invalidRequest = new UpdateStoreZoneRequest(null, null, null, -1);

            mockMvc.perform(put(
                                    BASE_URL + "/{storeZoneId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.displayOrder").exists());

            verifyNoInteractions(storeZoneService);
        }

        @Test
        @DisplayName("should accept a null displayOrder meaning unchanged")
        void updateZone_NullDisplayOrderIsUnchanged() throws Exception {
            var request = new UpdateStoreZoneRequest(null, null, null, null);
            when(storeZoneService.updateZone(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            any(UpdateStoreZoneRequest.class)))
                    .thenReturn(testStoreZoneResponse);

            mockMvc.perform(put(
                                    BASE_URL + "/{storeZoneId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 404 when the zone does not exist")
        void updateZone_NotFound() throws Exception {
            var request = new UpdateStoreZoneRequest("Z03", null, null, null);
            when(storeZoneService.updateZone(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            any(UpdateStoreZoneRequest.class)))
                    .thenThrow(new StoreZoneNotFoundException(testStoreZoneId));

            mockMvc.perform(put(
                                    BASE_URL + "/{storeZoneId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store zone not found with id: " + testStoreZoneId));
        }

        @Test
        @DisplayName("should return 409 when the new zone code collides inside the area")
        void updateZone_Duplicate() throws Exception {
            var request = new UpdateStoreZoneRequest("Z03", null, null, null);
            when(storeZoneService.updateZone(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            any(UpdateStoreZoneRequest.class)))
                    .thenThrow(
                            new DuplicateStoreZoneException("Store zone with code 'Z03' already exists in this area"));

            mockMvc.perform(put(
                                    BASE_URL + "/{storeZoneId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("Store zone with code 'Z03' already exists in this area"));
        }

        @Test
        @DisplayName("should return 412 with the precondition message when the version precondition fails")
        void updateZone_VersionConflictReturns412() throws Exception {
            var request = new UpdateStoreZoneRequest("Z03", null, null, null, 5L);
            when(storeZoneService.updateZone(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            any(UpdateStoreZoneRequest.class)))
                    .thenThrow(new VersionPreconditionException(
                            "The store zone conflicts with the current server state; reload and try again"));

            mockMvc.perform(put(
                                    BASE_URL + "/{storeZoneId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isPreconditionFailed())
                    .andExpect(jsonPath("$.status").value(412))
                    .andExpect(jsonPath("$.message")
                            .value("The store zone conflicts with the current server state; reload and try again"));
        }
    }

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{storeZoneId}")
    class DeleteZoneTests {

        @Test
        @DisplayName("should return 204 on soft delete")
        void deleteZone_Success() throws Exception {
            doNothing()
                    .when(storeZoneService)
                    .deleteZone(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId);

            mockMvc.perform(delete(
                            BASE_URL + "/{storeZoneId}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId))
                    .andExpect(status().isNoContent());

            verify(storeZoneService)
                    .deleteZone(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId);
        }

        @Test
        @DisplayName("should return 404 when the zone does not exist")
        void deleteZone_NotFound() throws Exception {
            doThrow(new StoreZoneNotFoundException(testStoreZoneId))
                    .when(storeZoneService)
                    .deleteZone(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId);

            mockMvc.perform(delete(
                            BASE_URL + "/{storeZoneId}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH " + BASE_URL + "/{storeZoneId}/enable")
    class EnableZoneTests {

        @Test
        @DisplayName("should return 200 with the re-enabled zone")
        void enableZone_Success() throws Exception {
            when(storeZoneService.enableZone(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId))
                    .thenReturn(testStoreZoneResponse);

            mockMvc.perform(patch(
                            BASE_URL + "/{storeZoneId}/enable",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when the zone does not exist")
        void enableZone_NotFound() throws Exception {
            when(storeZoneService.enableZone(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId))
                    .thenThrow(new StoreZoneNotFoundException(testStoreZoneId));

            mockMvc.perform(patch(
                            BASE_URL + "/{storeZoneId}/enable",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId))
                    .andExpect(status().isNotFound());
        }
    }
}
