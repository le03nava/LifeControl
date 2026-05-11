package com.lifecontrol.api.country.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lifecontrol.api.country.dto.CountryRequest;
import com.lifecontrol.api.country.dto.CountryResponse;
import com.lifecontrol.api.country.exception.CountryNotFoundException;
import com.lifecontrol.api.country.exception.DuplicateCountryException;
import com.lifecontrol.api.country.service.CountryService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CountryController Tests")
class CountryControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private CountryService countryService;

    @InjectMocks
    private CountryController countryController;

    private CountryResponse testCountryResponse;
    private UUID testCountryId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(countryController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        testCountryId = UUID.randomUUID();
        testCountryResponse = new CountryResponse(
                testCountryId, "MX", "México", true,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    @Nested
    @DisplayName("GET /api/countries")
    class GetAllCountriesTests {

        @Test
        @DisplayName("should return 200 with list of enabled countries")
        void getAllCountries_Default_Returns200() throws Exception {
            // Arrange
            when(countryService.getAllCountries(false)).thenReturn(List.of(testCountryResponse));

            // Act & Assert
            mockMvc.perform(get("/api/countries"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].countryCode").value("MX"))
                    .andExpect(jsonPath("$[0].countryName").value("México"));
        }

        @Test
        @DisplayName("should include disabled countries when ?includeDisabled=true")
        void getAllCountries_IncludeDisabled_ReturnsAll() throws Exception {
            // Arrange
            when(countryService.getAllCountries(true)).thenReturn(List.of(testCountryResponse));

            // Act & Assert
            mockMvc.perform(get("/api/countries?includeDisabled=true"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /api/countries/{id}")
    class GetCountryByIdTests {

        @Test
        @DisplayName("should return 200 when country exists")
        void getCountryById_Returns200() throws Exception {
            // Arrange
            when(countryService.getCountryById(testCountryId)).thenReturn(testCountryResponse);

            // Act & Assert
            mockMvc.perform(get("/api/countries/{id}", testCountryId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.countryCode").value("MX"));
        }

        @Test
        @DisplayName("should return 404 when country not found")
        void getCountryById_NotFound_Returns404() throws Exception {
            // Arrange
            when(countryService.getCountryById(testCountryId)).thenThrow(new CountryNotFoundException(testCountryId));

            // Act & Assert
            mockMvc.perform(get("/api/countries/{id}", testCountryId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/countries")
    class CreateCountryTests {

        @Test
        @DisplayName("should return 201 when created successfully")
        void createCountry_Returns201() throws Exception {
            // Arrange
            CountryRequest request = new CountryRequest("MX", "México");
            when(countryService.createCountry(any(CountryRequest.class))).thenReturn(testCountryResponse);

            // Act & Assert
            mockMvc.perform(post("/api/countries")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.countryCode").value("MX"));
        }

        @Test
        @DisplayName("should return 400 when countryCode is invalid")
        void createCountry_InvalidCode_Returns400() throws Exception {
            // Arrange
            CountryRequest invalidRequest = new CountryRequest("MEX", "México"); // 3 chars

            // Act & Assert
            mockMvc.perform(post("/api/countries")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 409 when countryCode already exists")
        void createCountry_DuplicateCode_Returns409() throws Exception {
            // Arrange
            CountryRequest request = new CountryRequest("MX", "México");
            when(countryService.createCountry(any(CountryRequest.class)))
                    .thenThrow(new DuplicateCountryException("Ya existe un país con código: MX"));

            // Act & Assert
            mockMvc.perform(post("/api/countries")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("PUT /api/countries/{id}")
    class UpdateCountryTests {

        @Test
        @DisplayName("should return 200 when updated successfully")
        void updateCountry_Returns200() throws Exception {
            // Arrange
            CountryRequest request = new CountryRequest("MX", "México actualizado");
            CountryResponse updatedResponse = new CountryResponse(
                    testCountryId, "MX", "México actualizado", true,
                    LocalDateTime.now(), LocalDateTime.now());
            when(countryService.updateCountry(eq(testCountryId), any(CountryRequest.class)))
                    .thenReturn(updatedResponse);

            // Act & Assert
            mockMvc.perform(put("/api/countries/{id}", testCountryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.countryName").value("México actualizado"));
        }

        @Test
        @DisplayName("should return 404 when country not found")
        void updateCountry_NotFound_Returns404() throws Exception {
            // Arrange
            CountryRequest request = new CountryRequest("MX", "México");
            when(countryService.updateCountry(eq(testCountryId), any(CountryRequest.class)))
                    .thenThrow(new CountryNotFoundException(testCountryId));

            // Act & Assert
            mockMvc.perform(put("/api/countries/{id}", testCountryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /api/countries/{id}")
    class DeleteCountryTests {

        @Test
        @DisplayName("should return 204 when deleted successfully")
        void deleteCountry_Returns204() throws Exception {
            // Act & Assert
            mockMvc.perform(delete("/api/countries/{id}", testCountryId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when country not found")
        void deleteCountry_NotFound_Returns404() throws Exception {
            // Arrange
            doThrow(new CountryNotFoundException(testCountryId))
                    .when(countryService).deleteCountry(testCountryId);

            // Act & Assert
            mockMvc.perform(delete("/api/countries/{id}", testCountryId))
                    .andExpect(status().isNotFound());
        }
    }
}
