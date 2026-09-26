package com.lifecontrol.api.store.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.exception.VersionPreconditionException;
import com.lifecontrol.api.store.dto.CreateStoreLocationRequest;
import com.lifecontrol.api.store.dto.StoreLocationResponse;
import com.lifecontrol.api.store.dto.UpdateStoreLocationRequest;
import com.lifecontrol.api.store.exception.DisabledParentException;
import com.lifecontrol.api.store.exception.DuplicateStoreLocationException;
import com.lifecontrol.api.store.exception.StoreLocationNotFoundException;
import com.lifecontrol.api.store.service.StoreLocationService;
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
@DisplayName("StoreLocationController Tests")
class StoreLocationControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private StoreLocationService storeLocationService;

    @InjectMocks
    private StoreLocationController storeLocationController;

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

    private static final String BASE_URL =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}/stores/{storeId}/areas/{areaId}/store-zones/{storeZoneId}/store-locations";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(storeLocationController)
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
    @DisplayName("GET " + BASE_URL)
    class GetAllLocationsTests {

        @Test
        @DisplayName("should return 200 with the list of locations")
        void getAllLocations_Success() throws Exception {
            when(storeLocationService.getAllLocations(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            false))
                    .thenReturn(List.of(testStoreLocationResponse));

            mockMvc.perform(get(
                            BASE_URL,
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].locationCode").value("L01"))
                    .andExpect(jsonPath("$[0].locationName").value("Estante"))
                    .andExpect(jsonPath("$[0].storeZoneId").value(testStoreZoneId.toString()))
                    .andExpect(jsonPath("$[0].storeAreaId").value(testAreaId.toString()))
                    .andExpect(jsonPath("$[0].flag").doesNotExist())
                    .andExpect(jsonPath("$[0].enabled").value(true));
        }

        @Test
        @DisplayName("should forward includeDisabled=true to the service")
        void getAllLocations_WithIncludeDisabled() throws Exception {
            when(storeLocationService.getAllLocations(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            true))
                    .thenReturn(List.of());

            mockMvc.perform(get(
                                    BASE_URL,
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId)
                            .param("includeDisabled", "true"))
                    .andExpect(status().isOk());

            verify(storeLocationService)
                    .getAllLocations(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            true);
        }

        @Test
        @DisplayName("should default includeDisabled to false")
        void getAllLocations_DefaultsToActiveOnly() throws Exception {
            when(storeLocationService.getAllLocations(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            false))
                    .thenReturn(List.of());

            mockMvc.perform(get(
                            BASE_URL,
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId))
                    .andExpect(status().isOk());

            verify(storeLocationService)
                    .getAllLocations(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            false);
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{storeLocationId}")
    class GetLocationByIdTests {

        @Test
        @DisplayName("should return 200 with the location")
        void getLocationById_Success() throws Exception {
            when(storeLocationService.getLocationById(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId))
                    .thenReturn(testStoreLocationResponse);

            mockMvc.perform(get(
                            BASE_URL + "/{storeLocationId}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testStoreLocationId.toString()))
                    .andExpect(jsonPath("$.locationCode").value("L01"));
        }

        @Test
        @DisplayName("should return 404 when the location does not exist")
        void getLocationById_NotFound() throws Exception {
            when(storeLocationService.getLocationById(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId))
                    .thenThrow(new StoreLocationNotFoundException(testStoreLocationId));

            mockMvc.perform(get(
                            BASE_URL + "/{storeLocationId}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store location not found with id: " + testStoreLocationId));
        }
    }

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateLocationTests {

        @Test
        @DisplayName("should return 201 with the created location")
        void createLocation_Success() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", "Ubicación de estante", 1);
            when(storeLocationService.createLocation(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            any(CreateStoreLocationRequest.class)))
                    .thenReturn(testStoreLocationResponse);

            mockMvc.perform(post(
                                    BASE_URL,
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.locationCode").value("L01"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 400 when locationCode is blank")
        void createLocation_ValidationError() throws Exception {
            var invalidRequest = new CreateStoreLocationRequest("", "Estante", null, null);

            mockMvc.perform(post(
                                    BASE_URL,
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
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.locationCode").exists());
        }

        @Test
        @DisplayName("should return 400 when locationCode exceeds 10 characters")
        void createLocation_LocationCodeTooLong() throws Exception {
            var invalidRequest = new CreateStoreLocationRequest("L0123456789", "Estante", null, null);

            mockMvc.perform(post(
                                    BASE_URL,
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
                    .andExpect(jsonPath("$.errors.locationCode").exists());
        }

        @Test
        @DisplayName("should return 400 when displayOrder is negative")
        void createLocation_NegativeDisplayOrderReturns400() throws Exception {
            var invalidRequest = new CreateStoreLocationRequest("L01", "Estante", null, -1);

            mockMvc.perform(post(
                                    BASE_URL,
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

            verifyNoInteractions(storeLocationService);
        }

        @Test
        @DisplayName("should accept displayOrder = 0")
        void createLocation_ZeroDisplayOrderIsAccepted() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", null, 0);
            when(storeLocationService.createLocation(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            any(CreateStoreLocationRequest.class)))
                    .thenReturn(testStoreLocationResponse);

            mockMvc.perform(post(
                                    BASE_URL,
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("should return 409 when the location code already exists in the zone")
        void createLocation_Duplicate() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", null, null);
            when(storeLocationService.createLocation(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            any(CreateStoreLocationRequest.class)))
                    .thenThrow(new DuplicateStoreLocationException(
                            "Store location with code 'L01' already exists in this zone"));

            mockMvc.perform(post(
                                    BASE_URL,
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
                    .andExpect(
                            jsonPath("$.message").value("Store location with code 'L01' already exists in this zone"));
        }

        @Test
        @DisplayName("should return 409 when the parent zone is disabled")
        void createLocation_DisabledParentZone_Returns409() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", null, null);
            when(storeLocationService.createLocation(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            any(CreateStoreLocationRequest.class)))
                    .thenThrow(new DisabledParentException(
                            "Cannot create a store location: store zone with id " + testStoreZoneId + " is disabled"));

            mockMvc.perform(post(
                                    BASE_URL,
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
                    .andExpect(jsonPath("$.message")
                            .value("Cannot create a store location: store zone with id " + testStoreZoneId
                                    + " is disabled"));
        }

        @Test
        @DisplayName("should return 409 when a concurrent duplicate surfaces as DataIntegrityViolationException")
        void createLocation_DataIntegrityViolation_Returns409() throws Exception {
            var request = new CreateStoreLocationRequest("L01", "Estante", null, null);
            when(storeLocationService.createLocation(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            any(CreateStoreLocationRequest.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

            mockMvc.perform(post(
                                    BASE_URL,
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{storeLocationId}")
    class UpdateLocationTests {

        @Test
        @DisplayName("should return 200 with the updated location")
        void updateLocation_Success() throws Exception {
            var request = new UpdateStoreLocationRequest("L03", "Estante", "Actualizada", 3);
            var updatedResponse = new StoreLocationResponse(
                    testStoreLocationId,
                    testStoreZoneId,
                    testAreaId,
                    testStoreId,
                    testCompanyId,
                    testCompanyCountryId,
                    testRegionId,
                    testZoneId,
                    "L03",
                    "Estante",
                    "Actualizada",
                    3,
                    true,
                    now,
                    now,
                    0L);
            when(storeLocationService.updateLocation(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            eq(testStoreLocationId),
                            any(UpdateStoreLocationRequest.class)))
                    .thenReturn(updatedResponse);

            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId,
                                    testStoreLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.locationCode").value("L03"))
                    .andExpect(jsonPath("$.locationName").value("Estante"))
                    .andExpect(jsonPath("$.displayOrder").value(3));
        }

        @Test
        @DisplayName("should return 400 when a provided locationName is blank")
        void updateLocation_BlankName() throws Exception {
            var invalidRequest = new UpdateStoreLocationRequest(null, "", null, null);

            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId,
                                    testStoreLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.locationName").exists());
        }

        @Test
        @DisplayName("should return 400 when displayOrder is negative")
        void updateLocation_NegativeDisplayOrderReturns400() throws Exception {
            var invalidRequest = new UpdateStoreLocationRequest(null, null, null, -1);

            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId,
                                    testStoreLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.displayOrder").exists());

            verifyNoInteractions(storeLocationService);
        }

        @Test
        @DisplayName("should accept a null displayOrder meaning unchanged")
        void updateLocation_NullDisplayOrderIsUnchanged() throws Exception {
            var request = new UpdateStoreLocationRequest(null, null, null, null);
            when(storeLocationService.updateLocation(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            eq(testStoreLocationId),
                            any(UpdateStoreLocationRequest.class)))
                    .thenReturn(testStoreLocationResponse);

            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId,
                                    testStoreLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 404 when the location does not exist")
        void updateLocation_NotFound() throws Exception {
            var request = new UpdateStoreLocationRequest("L03", null, null, null);
            when(storeLocationService.updateLocation(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            eq(testStoreLocationId),
                            any(UpdateStoreLocationRequest.class)))
                    .thenThrow(new StoreLocationNotFoundException(testStoreLocationId));

            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId,
                                    testStoreLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Store location not found with id: " + testStoreLocationId));
        }

        @Test
        @DisplayName("should return 409 when the new location code collides inside the zone")
        void updateLocation_Duplicate() throws Exception {
            var request = new UpdateStoreLocationRequest("L03", null, null, null);
            when(storeLocationService.updateLocation(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            eq(testStoreLocationId),
                            any(UpdateStoreLocationRequest.class)))
                    .thenThrow(new DuplicateStoreLocationException(
                            "Store location with code 'L03' already exists in this zone"));

            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId,
                                    testStoreLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(
                            jsonPath("$.message").value("Store location with code 'L03' already exists in this zone"));
        }

        @Test
        @DisplayName("should return 412 with the precondition message when the version precondition fails")
        void updateLocation_VersionConflictReturns412() throws Exception {
            var request = new UpdateStoreLocationRequest("L03", null, null, null, 5L);
            when(storeLocationService.updateLocation(
                            eq(testCompanyId),
                            eq(testCompanyCountryId),
                            eq(testRegionId),
                            eq(testZoneId),
                            eq(testStoreId),
                            eq(testAreaId),
                            eq(testStoreZoneId),
                            eq(testStoreLocationId),
                            any(UpdateStoreLocationRequest.class)))
                    .thenThrow(new VersionPreconditionException(
                            "The store location conflicts with the current server state; reload and try again"));

            mockMvc.perform(put(
                                    BASE_URL + "/{storeLocationId}",
                                    testCompanyId,
                                    testCompanyCountryId,
                                    testRegionId,
                                    testZoneId,
                                    testStoreId,
                                    testAreaId,
                                    testStoreZoneId,
                                    testStoreLocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isPreconditionFailed())
                    .andExpect(jsonPath("$.status").value(412))
                    .andExpect(jsonPath("$.message")
                            .value("The store location conflicts with the current server state; reload and try again"));
        }
    }

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{storeLocationId}")
    class DeleteLocationTests {

        @Test
        @DisplayName("should return 204 on soft delete")
        void deleteLocation_Success() throws Exception {
            doNothing()
                    .when(storeLocationService)
                    .deleteLocation(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId);

            mockMvc.perform(delete(
                            BASE_URL + "/{storeLocationId}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId))
                    .andExpect(status().isNoContent());

            verify(storeLocationService)
                    .deleteLocation(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId);
        }

        @Test
        @DisplayName("should return 404 when the location does not exist")
        void deleteLocation_NotFound() throws Exception {
            doThrow(new StoreLocationNotFoundException(testStoreLocationId))
                    .when(storeLocationService)
                    .deleteLocation(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId);

            mockMvc.perform(delete(
                            BASE_URL + "/{storeLocationId}",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH " + BASE_URL + "/{storeLocationId}/enable")
    class EnableLocationTests {

        @Test
        @DisplayName("should return 200 with the re-enabled location")
        void enableLocation_Success() throws Exception {
            when(storeLocationService.enableLocation(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId))
                    .thenReturn(testStoreLocationResponse);

            mockMvc.perform(patch(
                            BASE_URL + "/{storeLocationId}/enable",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when the location does not exist")
        void enableLocation_NotFound() throws Exception {
            when(storeLocationService.enableLocation(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId))
                    .thenThrow(new StoreLocationNotFoundException(testStoreLocationId));

            mockMvc.perform(patch(
                            BASE_URL + "/{storeLocationId}/enable",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 409 when the parent zone is disabled")
        void enableLocation_DisabledParentZone_Returns409() throws Exception {
            when(storeLocationService.enableLocation(
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId))
                    .thenThrow(new DisabledParentException("Cannot re-enable a store location: store zone with id "
                            + testStoreZoneId + " is disabled"));

            mockMvc.perform(patch(
                            BASE_URL + "/{storeLocationId}/enable",
                            testCompanyId,
                            testCompanyCountryId,
                            testRegionId,
                            testZoneId,
                            testStoreId,
                            testAreaId,
                            testStoreZoneId,
                            testStoreLocationId))
                    .andExpect(status().isConflict());
        }
    }
}
