package com.lifecontrol.api.company.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.company.dto.CompanyRegionResponse;
import com.lifecontrol.api.company.dto.CreateCompanyRegionRequest;
import com.lifecontrol.api.company.dto.UpdateCompanyRegionRequest;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyRegionException;
import com.lifecontrol.api.company.service.CompanyRegionService;
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
@DisplayName("CompanyRegionController Tests")
class CompanyRegionControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private CompanyRegionService companyRegionService;

    @InjectMocks
    private CompanyRegionController companyRegionController;

    private UUID testCompanyId;
    private UUID testCountryId;
    private UUID testRegionId;
    private CompanyRegionResponse testRegionResponse;
    private LocalDateTime now;

    private static final String BASE_URL = "/api/companies/{companyId}/countries/{countryId}/regions";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(companyRegionController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testCompanyId = UUID.randomUUID();
        testCountryId = UUID.randomUUID();
        testRegionId = UUID.randomUUID();
        now = LocalDateTime.now();

        testRegionResponse = new CompanyRegionResponse(
                testRegionId,
                UUID.randomUUID(),
                testCompanyId,
                testCountryId,
                "NORTE",
                "Norte",
                true,
                now,
                now
        );
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAllRegionsTests {

        @Test
        @DisplayName("should return 200 with list of regions")
        void getAllRegions_Success() throws Exception {
            // Arrange
            when(companyRegionService.getAllRegions(testCompanyId, testCountryId, false))
                    .thenReturn(List.of(testRegionResponse));

            // Act & Assert
            mockMvc.perform(get(BASE_URL, testCompanyId, testCountryId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].regionCode").value("NORTE"))
                    .andExpect(jsonPath("$[0].regionName").value("Norte"))
                    .andExpect(jsonPath("$[0].enabled").value(true));
        }

        @Test
        @DisplayName("should pass includeDisabled query param to service")
        void getAllRegions_WithIncludeDisabled() throws Exception {
            // Arrange
            when(companyRegionService.getAllRegions(testCompanyId, testCountryId, true))
                    .thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(get(BASE_URL, testCompanyId, testCountryId)
                            .param("includeDisabled", "true"))
                    .andExpect(status().isOk());
            verify(companyRegionService).getAllRegions(testCompanyId, testCountryId, true);
        }

        @Test
        @DisplayName("should return empty list when no regions")
        void getAllRegions_EmptyList() throws Exception {
            // Arrange
            when(companyRegionService.getAllRegions(testCompanyId, testCountryId, false))
                    .thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(get(BASE_URL, testCompanyId, testCountryId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{id}")
    class GetRegionByIdTests {

        @Test
        @DisplayName("should return 200 with region")
        void getRegionById_Success() throws Exception {
            // Arrange
            when(companyRegionService.getRegionById(testCompanyId, testCountryId, testRegionId))
                    .thenReturn(testRegionResponse);

            // Act & Assert
            mockMvc.perform(get(BASE_URL + "/{id}", testCompanyId, testCountryId, testRegionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.regionCode").value("NORTE"))
                    .andExpect(jsonPath("$.regionName").value("Norte"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when region not found")
        void getRegionById_NotFound() throws Exception {
            // Arrange
            when(companyRegionService.getRegionById(testCompanyId, testCountryId, testRegionId))
                    .thenThrow(new CompanyRegionNotFoundException("Company region not found with id: " + testRegionId));

            // Act & Assert
            mockMvc.perform(get(BASE_URL + "/{id}", testCompanyId, testCountryId, testRegionId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Company region not found with id: " + testRegionId));
        }
    }

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateRegionTests {

        @Test
        @DisplayName("should return 201 when region created")
        void createRegion_Success() throws Exception {
            // Arrange
            var request = new CreateCompanyRegionRequest("NORTE", "Norte");
            when(companyRegionService.createRegion(eq(testCompanyId), eq(testCountryId), any(CreateCompanyRegionRequest.class)))
                    .thenReturn(testRegionResponse);

            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCountryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.regionCode").value("NORTE"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 400 when request body is invalid")
        void createRegion_ValidationError() throws Exception {
            // Arrange
            var invalidRequest = new CreateCompanyRegionRequest("", "");

            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCountryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"));
        }

        @Test
        @DisplayName("should return 409 when duplicate region code")
        void createRegion_DuplicateCode() throws Exception {
            // Arrange
            var request = new CreateCompanyRegionRequest("NORTE", "Norte");
            when(companyRegionService.createRegion(eq(testCompanyId), eq(testCountryId), any(CreateCompanyRegionRequest.class)))
                    .thenThrow(new DuplicateCompanyRegionException(
                            "Company region with code 'NORTE' already exists for this country"));

            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCountryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Company region with code 'NORTE' already exists for this country"));
        }

        @Test
        @DisplayName("should return 400 when request body is empty")
        void createRegion_EmptyBody() throws Exception {
            // Act & Assert
            mockMvc.perform(post(BASE_URL, testCompanyId, testCountryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class UpdateRegionTests {

        @Test
        @DisplayName("should return 200 when region updated")
        void updateRegion_Success() throws Exception {
            // Arrange
            var request = new UpdateCompanyRegionRequest("NORTE", "Norte Actualizado");
            var updatedResponse = new CompanyRegionResponse(
                    testRegionId, UUID.randomUUID(), testCompanyId, testCountryId,
                    "NORTE", "Norte Actualizado", true, now, now);
            when(companyRegionService.updateRegion(eq(testCompanyId), eq(testCountryId),
                    eq(testRegionId), any(UpdateCompanyRegionRequest.class)))
                    .thenReturn(updatedResponse);

            // Act & Assert
            mockMvc.perform(put(BASE_URL + "/{id}", testCompanyId, testCountryId, testRegionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.regionName").value("Norte Actualizado"))
                    .andExpect(jsonPath("$.regionCode").value("NORTE"));
        }

        @Test
        @DisplayName("should return 400 when update body is invalid")
        void updateRegion_ValidationError() throws Exception {
            var invalidRequest = new UpdateCompanyRegionRequest("", "");
            mockMvc.perform(put(BASE_URL + "/{id}", testCompanyId, testCountryId, testRegionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when region not found on update")
        void updateRegion_NotFound() throws Exception {
            var request = new UpdateCompanyRegionRequest("NORTE", "Norte");
            when(companyRegionService.updateRegion(eq(testCompanyId), eq(testCountryId),
                    eq(testRegionId), any(UpdateCompanyRegionRequest.class)))
                    .thenThrow(new CompanyRegionNotFoundException("Company region not found with id: " + testRegionId));

            mockMvc.perform(put(BASE_URL + "/{id}", testCompanyId, testCountryId, testRegionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 409 when update creates duplicate code")
        void updateRegion_DuplicateCode() throws Exception {
            var request = new UpdateCompanyRegionRequest("SUR", "Sur");
            when(companyRegionService.updateRegion(eq(testCompanyId), eq(testCountryId),
                    eq(testRegionId), any(UpdateCompanyRegionRequest.class)))
                    .thenThrow(new DuplicateCompanyRegionException(
                            "Company region with code 'SUR' already exists for this country"));

            mockMvc.perform(put(BASE_URL + "/{id}", testCompanyId, testCountryId, testRegionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class DeleteRegionTests {

        @Test
        @DisplayName("should return 204 when region soft-deleted")
        void deleteRegion_Success() throws Exception {
            // Arrange
            doNothing().when(companyRegionService).deleteRegion(testCompanyId, testCountryId, testRegionId);

            // Act & Assert
            mockMvc.perform(delete(BASE_URL + "/{id}", testCompanyId, testCountryId, testRegionId))
                    .andExpect(status().isNoContent());
            verify(companyRegionService).deleteRegion(testCompanyId, testCountryId, testRegionId);
        }

        @Test
        @DisplayName("should return 404 when region not found on delete")
        void deleteRegion_NotFound() throws Exception {
            // Arrange
            doThrow(new CompanyRegionNotFoundException("Company region not found with id: " + testRegionId))
                    .when(companyRegionService).deleteRegion(testCompanyId, testCountryId, testRegionId);

            // Act & Assert
            mockMvc.perform(delete(BASE_URL + "/{id}", testCompanyId, testCountryId, testRegionId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Company region not found with id: " + testRegionId));
        }
    }
}
