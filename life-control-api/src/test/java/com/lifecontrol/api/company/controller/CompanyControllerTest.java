package com.lifecontrol.api.company.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.dto.CompanyResponse;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyException;
import com.lifecontrol.api.company.service.CompanyService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyController Tests")
class CompanyControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private CompanyService companyService;

    @InjectMocks
    private CompanyController companyController;

    private CompanyResponse testCompanyResponse;
    private CompanyRequest testCompanyRequest;
    private UUID testCompanyId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(companyController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testCompanyId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        testCompanyResponse = CompanyResponse.builder()
                .companyId(1)
                .companyKey("COMPANY_1")
                .companyName("Test Company")
                .tipoPersonaId(1)
                .razonSocial("Razon Social Test SA de CV")
                .rfc("XAXX010101000")
                .phone("+1234567890")
                .email("test@company.com")
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        testCompanyRequest = CompanyRequest.builder()
                .companyId(1)
                .companyName("Updated Company")
                .tipoPersonaId(2)
                .razonSocial("Nueva Razon Social")
                .rfc("XAXX010101000")
                .phone("+9876543210")
                .email("updated@company.com")
                .enabled(false)
                .build();
    }

    @Nested
    @DisplayName("PUT /api/companies/{id}")
    class UpdateCompanyTests {

        @Test
        @DisplayName("updateCompany - should return 200 OK with updated company")
        void updateCompany_Success() throws Exception {
            // Arrange
            when(companyService.updateCompany(eq(testCompanyId), any(CompanyRequest.class)))
                    .thenReturn(testCompanyResponse);

            // Act & Assert
            mockMvc.perform(put("/api/companies/{id}", testCompanyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testCompanyRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.companyName").value("Test Company"));
        }

        @Test
        @DisplayName("updateCompany - should return 404 Not Found when company not exists")
        void updateCompany_NotFound() throws Exception {
            // Arrange
            when(companyService.updateCompany(eq(testCompanyId), any(CompanyRequest.class)))
                    .thenThrow(new CompanyNotFoundException(testCompanyId));

            // Act & Assert
            mockMvc.perform(put("/api/companies/{id}", testCompanyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testCompanyRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Company not found with id: " + testCompanyId));
        }

        @Test
        @DisplayName("updateCompany - should return 409 Conflict for duplicate RFC")
        void updateCompany_DuplicateRfc_Conflict() throws Exception {
            // Arrange
            when(companyService.updateCompany(eq(testCompanyId), any(CompanyRequest.class)))
                    .thenThrow(new DuplicateCompanyException("Ya existe una compañía con RFC: XAXX010101000"));

            // Act & Assert
            mockMvc.perform(put("/api/companies/{id}", testCompanyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testCompanyRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Ya existe una compañía con RFC: XAXX010101000"));
        }

        @Test
        @DisplayName("updateCompany - should return 400 Bad Request for invalid input")
        void updateCompany_InvalidInput_BadRequest() throws Exception {
            // Arrange - missing required field companyName
            CompanyRequest invalidRequest = CompanyRequest.builder()
                    .companyId(1)
                    // companyName is required but missing
                    .rfc("XAXX010101000")
                    .build();

            // Act & Assert
            mockMvc.perform(put("/api/companies/{id}", testCompanyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }
    }
}
