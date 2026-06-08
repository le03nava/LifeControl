package com.lifecontrol.api.company.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.company.dto.CompanyCountryRequest;
import com.lifecontrol.api.company.dto.CompanyCountryResponse;
import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyCountryException;
import com.lifecontrol.api.company.service.CompanyCountryService;
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
@DisplayName("CompanyCountryController Tests")
class CompanyCountryControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private CompanyCountryService companyCountryService;

    @InjectMocks
    private CompanyCountryController companyCountryController;

    private UUID testCompanyId;
    private UUID testCountryRelationId;
    private CompanyCountryResponse testCountryResponse;
    private CompanyCountryRequest testCountryRequest;
    private LocalDateTime now;

    private static final String BASE_URL = "/api/companies/{companyId}/countries";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(companyCountryController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testCompanyId = UUID.randomUUID();
        testCountryRelationId = UUID.randomUUID();
        now = LocalDateTime.now();

        testCountryResponse = new CompanyCountryResponse(
                testCountryRelationId,
                testCompanyId,
                UUID.randomUUID(),
                "MX",
                "México",
                "Mexican market",
                now,
                now
        );

        testCountryRequest = new CompanyCountryRequest("MX", "México");
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAllCountriesTests {

        @Test
        @DisplayName("should return 200 with list of countries")
        void getAllCountries_Success() throws Exception {
            when(companyCountryService.getCountriesByCompanyId(testCompanyId))
                    .thenReturn(List.of(testCountryResponse));

            mockMvc.perform(get(BASE_URL, testCompanyId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].countryCode").value("MX"))
                    .andExpect(jsonPath("$[0].countryName").value("México"))
                    .andExpect(jsonPath("$[0].localAlias").value("Mexican market"));
        }

        @Test
        @DisplayName("should return 200 with empty list when no countries")
        void getAllCountries_EmptyList() throws Exception {
            when(companyCountryService.getCountriesByCompanyId(testCompanyId))
                    .thenReturn(List.of());

            mockMvc.perform(get(BASE_URL, testCompanyId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("should return 404 when company not found")
        void getAllCountries_CompanyNotFound() throws Exception {
            when(companyCountryService.getCountriesByCompanyId(testCompanyId))
                    .thenThrow(new CompanyNotFoundException(testCompanyId));

            mockMvc.perform(get(BASE_URL, testCompanyId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Company not found with id: " + testCompanyId));
        }
    }

    @Nested
    @DisplayName("POST " + BASE_URL)
    class AddCountryTests {

        @Test
        @DisplayName("should return 201 when country added")
        void addCountry_Success() throws Exception {
            when(companyCountryService.addCountryToCompany(eq(testCompanyId), any(CompanyCountryRequest.class)))
                    .thenReturn(testCountryResponse);

            mockMvc.perform(post(BASE_URL, testCompanyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testCountryRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.countryCode").value("MX"))
                    .andExpect(jsonPath("$.countryName").value("México"));
        }

        @Test
        @DisplayName("should return 400 when request body is invalid")
        void addCountry_ValidationError() throws Exception {
            var invalidRequest = new CompanyCountryRequest("", "");

            mockMvc.perform(post(BASE_URL, testCompanyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"));
        }

        @Test
        @DisplayName("should return 409 when duplicate country association")
        void addCountry_Duplicate() throws Exception {
            when(companyCountryService.addCountryToCompany(eq(testCompanyId), any(CompanyCountryRequest.class)))
                    .thenThrow(new DuplicateCompanyCountryException("MX"));

            mockMvc.perform(post(BASE_URL, testCompanyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testCountryRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("The company already has a relationship with country: MX"));
        }

        @Test
        @DisplayName("should return 404 when company not found")
        void addCountry_CompanyNotFound() throws Exception {
            when(companyCountryService.addCountryToCompany(eq(testCompanyId), any(CompanyCountryRequest.class)))
                    .thenThrow(new CompanyNotFoundException(testCompanyId));

            mockMvc.perform(post(BASE_URL, testCompanyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testCountryRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Company not found with id: " + testCompanyId));
        }
    }

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class UpdateCountryTests {

        @Test
        @DisplayName("should return 200 when country updated")
        void updateCountry_Success() throws Exception {
            var updatedResponse = new CompanyCountryResponse(
                    testCountryRelationId, testCompanyId, UUID.randomUUID(),
                    "US", "United States", "US market", now, now);
            when(companyCountryService.updateCountry(eq(testCompanyId), eq(testCountryRelationId), any(CompanyCountryRequest.class)))
                    .thenReturn(updatedResponse);

            mockMvc.perform(put(BASE_URL + "/{id}", testCompanyId, testCountryRelationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new CompanyCountryRequest("US", "United States"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.countryCode").value("US"))
                    .andExpect(jsonPath("$.countryName").value("United States"));
        }

        @Test
        @DisplayName("should return 404 when company-country relation not found")
        void updateCountry_NotFound() throws Exception {
            when(companyCountryService.updateCountry(eq(testCompanyId), eq(testCountryRelationId), any(CompanyCountryRequest.class)))
                    .thenThrow(new CompanyCountryNotFoundException(testCountryRelationId));

            mockMvc.perform(put(BASE_URL + "/{id}", testCompanyId, testCountryRelationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testCountryRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Company-country relation not found with id: " + testCountryRelationId));
        }

        @Test
        @DisplayName("should return 400 when update body is invalid")
        void updateCountry_ValidationError() throws Exception {
            var invalidRequest = new CompanyCountryRequest("", "");

            mockMvc.perform(put(BASE_URL + "/{id}", testCompanyId, testCountryRelationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"));
        }

        @Test
        @DisplayName("should return 409 when update creates duplicate code")
        void updateCountry_DuplicateCode() throws Exception {
            when(companyCountryService.updateCountry(eq(testCompanyId), eq(testCountryRelationId), any(CompanyCountryRequest.class)))
                    .thenThrow(new DuplicateCompanyCountryException("US"));

            mockMvc.perform(put(BASE_URL + "/{id}", testCompanyId, testCountryRelationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new CompanyCountryRequest("US", "United States"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("The company already has a relationship with country: US"));
        }
    }

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class RemoveCountryTests {

        @Test
        @DisplayName("should return 204 when country removed")
        void removeCountry_Success() throws Exception {
            doNothing().when(companyCountryService).removeCountryFromCompany(testCompanyId, testCountryRelationId);

            mockMvc.perform(delete(BASE_URL + "/{id}", testCompanyId, testCountryRelationId))
                    .andExpect(status().isNoContent());
            verify(companyCountryService).removeCountryFromCompany(testCompanyId, testCountryRelationId);
        }

        @Test
        @DisplayName("should return 404 when company-country relation not found")
        void removeCountry_NotFound() throws Exception {
            doThrow(new CompanyCountryNotFoundException(testCountryRelationId))
                    .when(companyCountryService).removeCountryFromCompany(testCompanyId, testCountryRelationId);

            mockMvc.perform(delete(BASE_URL + "/{id}", testCompanyId, testCountryRelationId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Company-country relation not found with id: " + testCountryRelationId));
        }
    }
}
