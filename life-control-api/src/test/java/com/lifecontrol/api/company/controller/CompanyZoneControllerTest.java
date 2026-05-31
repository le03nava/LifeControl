package com.lifecontrol.api.company.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.company.dto.CompanyZoneResponse;
import com.lifecontrol.api.company.dto.CreateCompanyZoneRequest;
import com.lifecontrol.api.company.dto.UpdateCompanyZoneRequest;
import com.lifecontrol.api.company.exception.CompanyZoneNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyZoneException;
import com.lifecontrol.api.company.service.CompanyZoneService;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyZoneController Tests")
class CompanyZoneControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private CompanyZoneService companyZoneService;

    @InjectMocks
    private CompanyZoneController companyZoneController;

    private UUID testCompanyId;
    private UUID testCompanyCountryId;
    private UUID testRegionId;
    private UUID testZoneId;
    private CompanyZoneResponse testZoneResponse;
    private LocalDateTime now;

    private static final String BASE_URL = "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(companyZoneController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testCompanyId = UUID.randomUUID();
        testCompanyCountryId = UUID.randomUUID();
        testRegionId = UUID.randomUUID();
        testZoneId = UUID.randomUUID();
        now = LocalDateTime.now();

        testZoneResponse = new CompanyZoneResponse(
                testZoneId,
                testRegionId,
                testCompanyCountryId,
                testCompanyId,
                UUID.randomUUID(),
                "CEN",
                "Centro",
                null,
                null,
                true,
                now,
                now
        );
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAllZonesTests {

        @Test
        @DisplayName("should return 200 with list of zones")
        void getAllZones_Success() throws Exception {
            // Arrange
            when(companyZoneService.getAllZones(testCompanyId, testCompanyCountryId, testRegionId, false))
                    .thenReturn(List.of(testZoneResponse));

            // Act & Assert
            mockMvc.perform(get(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].zoneCode").value("CEN"))
                    .andExpect(jsonPath("$[0].zoneName").value("Centro"))
                    .andExpect(jsonPath("$[0].enabled").value(true));
        }

        @Test
        @DisplayName("should pass includeDisabled query param to service")
        void getAllZones_WithIncludeDisabled() throws Exception {
            // Arrange
            when(companyZoneService.getAllZones(testCompanyId, testCompanyCountryId, testRegionId, true))
                    .thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(get(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId)
                            .param("includeDisabled", "true"))
                    .andExpect(status().isOk());
            verify(companyZoneService).getAllZones(testCompanyId, testCompanyCountryId, testRegionId, true);
        }

        @Test
        @DisplayName("should return empty list when no zones")
        void getAllZones_EmptyList() throws Exception {
            // Arrange
            when(companyZoneService.getAllZones(testCompanyId, testCompanyCountryId, testRegionId, false))
                    .thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(get(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{id}")
    class GetZoneByIdTests {

        @Test
        @DisplayName("should return 200 with zone")
        void getZoneById_Success() throws Exception {
            // Arrange
            when(companyZoneService.getZoneById(testCompanyId, testCompanyCountryId, testRegionId, testZoneId))
                    .thenReturn(testZoneResponse);

            // Act & Assert
            mockMvc.perform(get(BASE_URL + "/{id}", testCompanyId, testCompanyCountryId, testRegionId, testZoneId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.zoneCode").value("CEN"))
                    .andExpect(jsonPath("$.zoneName").value("Centro"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when zone not found")
        void getZoneById_NotFound() throws Exception {
            // Arrange
            when(companyZoneService.getZoneById(testCompanyId, testCompanyCountryId, testRegionId, testZoneId))
                    .thenThrow(new CompanyZoneNotFoundException("Company zone not found with id: " + testZoneId));

            // Act & Assert
            mockMvc.perform(get(BASE_URL + "/{id}", testCompanyId, testCompanyCountryId, testRegionId, testZoneId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Company zone not found with id: " + testZoneId));
        }
    }

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateZoneTests {

        @Test
        @DisplayName("should return 201 when zone created")
        void createZone_Success() throws Exception {
            // Arrange
            var request = new CreateCompanyZoneRequest("CEN", "Centro", null, null);
            when(companyZoneService.createZone(eq(testCompanyId), eq(testCompanyCountryId), eq(testRegionId), any(CreateCompanyZoneRequest.class)))
                    .thenReturn(testZoneResponse);

            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.zoneCode").value("CEN"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 400 when request body is invalid")
        void createZone_ValidationError() throws Exception {
            // Arrange
            var invalidRequest = new CreateCompanyZoneRequest("", "", null, null);

            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"));
        }

        @Test
        @DisplayName("should return 409 when duplicate zone code")
        void createZone_DuplicateCode() throws Exception {
            // Arrange
            var request = new CreateCompanyZoneRequest("CEN", "Centro", null, null);
            when(companyZoneService.createZone(eq(testCompanyId), eq(testCompanyCountryId), eq(testRegionId), any(CreateCompanyZoneRequest.class)))
                    .thenThrow(new DuplicateCompanyZoneException(
                            "Company zone with code 'CEN' already exists for this region"));

            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Company zone with code 'CEN' already exists for this region"));
        }

        @Test
        @DisplayName("should return 400 when request body is empty")
        void createZone_EmptyBody() throws Exception {
            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCompanyCountryId, testRegionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class UpdateZoneTests {

        @Test
        @DisplayName("should return 200 when zone updated")
        void updateZone_Success() throws Exception {
            // Arrange
            var request = new UpdateCompanyZoneRequest("CEN", "Centro Actualizado", null, null);
            var updatedResponse = new CompanyZoneResponse(
                    testZoneId, testRegionId, testCompanyCountryId, testCompanyId, UUID.randomUUID(),
                    "CEN", "Centro Actualizado", null, null, true, now, now);
            when(companyZoneService.updateZone(eq(testCompanyId), eq(testCompanyCountryId),
                    eq(testRegionId), eq(testZoneId), any(UpdateCompanyZoneRequest.class)))
                    .thenReturn(updatedResponse);

            // Act & Assert
            mockMvc.perform(put(BASE_URL + "/{id}", testCompanyId, testCompanyCountryId, testRegionId, testZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.zoneName").value("Centro Actualizado"))
                    .andExpect(jsonPath("$.zoneCode").value("CEN"));
        }

        @Test
        @DisplayName("should return 400 when update body is invalid")
        void updateZone_ValidationError() throws Exception {
            var invalidRequest = new UpdateCompanyZoneRequest("", "", null, null);
            mockMvc.perform(put(BASE_URL + "/{id}", testCompanyId, testCompanyCountryId, testRegionId, testZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when zone not found on update")
        void updateZone_NotFound() throws Exception {
            var request = new UpdateCompanyZoneRequest("CEN", "Centro", null, null);
            when(companyZoneService.updateZone(eq(testCompanyId), eq(testCompanyCountryId),
                    eq(testRegionId), eq(testZoneId), any(UpdateCompanyZoneRequest.class)))
                    .thenThrow(new CompanyZoneNotFoundException("Company zone not found with id: " + testZoneId));

            mockMvc.perform(put(BASE_URL + "/{id}", testCompanyId, testCompanyCountryId, testRegionId, testZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 409 when update creates duplicate code")
        void updateZone_DuplicateCode() throws Exception {
            var request = new UpdateCompanyZoneRequest("SUR", "Sur", null, null);
            when(companyZoneService.updateZone(eq(testCompanyId), eq(testCompanyCountryId),
                    eq(testRegionId), eq(testZoneId), any(UpdateCompanyZoneRequest.class)))
                    .thenThrow(new DuplicateCompanyZoneException(
                            "Company zone with code 'SUR' already exists for this region"));

            mockMvc.perform(put(BASE_URL + "/{id}", testCompanyId, testCompanyCountryId, testRegionId, testZoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class DeleteZoneTests {

        @Test
        @DisplayName("should return 204 when zone soft-deleted")
        void deleteZone_Success() throws Exception {
            // Arrange
            doNothing().when(companyZoneService).deleteZone(testCompanyId, testCompanyCountryId, testRegionId, testZoneId);

            // Act & Assert
            mockMvc.perform(delete(BASE_URL + "/{id}", testCompanyId, testCompanyCountryId, testRegionId, testZoneId))
                    .andExpect(status().isNoContent());
            verify(companyZoneService).deleteZone(testCompanyId, testCompanyCountryId, testRegionId, testZoneId);
        }

        @Test
        @DisplayName("should return 404 when zone not found on delete")
        void deleteZone_NotFound() throws Exception {
            // Arrange
            doThrow(new CompanyZoneNotFoundException("Company zone not found with id: " + testZoneId))
                    .when(companyZoneService).deleteZone(testCompanyId, testCompanyCountryId, testRegionId, testZoneId);

            // Act & Assert
            mockMvc.perform(delete(BASE_URL + "/{id}", testCompanyId, testCompanyCountryId, testRegionId, testZoneId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Company zone not found with id: " + testZoneId));
        }
    }

    @Nested
    @DisplayName("PATCH " + BASE_URL + "/{id}")
    class EnableZoneTests {

        @Test
        @DisplayName("should return 200 when zone re-enabled")
        void enableZone_Success() throws Exception {
            // Arrange
            var enabledResponse = new CompanyZoneResponse(
                    testZoneId, testRegionId, testCompanyCountryId, testCompanyId, UUID.randomUUID(),
                    "CEN", "Centro", null, null, true, now, now);
            when(companyZoneService.enableZone(testCompanyId, testCompanyCountryId, testRegionId, testZoneId))
                    .thenReturn(enabledResponse);

            // Act & Assert
            mockMvc.perform(patch(BASE_URL + "/{id}", testCompanyId, testCompanyCountryId, testRegionId, testZoneId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.zoneCode").value("CEN"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when zone not found on enable")
        void enableZone_NotFound() throws Exception {
            // Arrange
            when(companyZoneService.enableZone(testCompanyId, testCompanyCountryId, testRegionId, testZoneId))
                    .thenThrow(new CompanyZoneNotFoundException("Company zone not found with id: " + testZoneId));

            // Act & Assert
            mockMvc.perform(patch(BASE_URL + "/{id}", testCompanyId, testCompanyCountryId, testRegionId, testZoneId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Company zone not found with id: " + testZoneId));
        }
    }
}
