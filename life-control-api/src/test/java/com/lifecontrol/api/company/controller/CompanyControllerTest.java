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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testCompanyId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        testCompanyResponse = new CompanyResponse(
                testCompanyId,
 "1",
                "Test Company",
                1,
                "Razon Social Test SA de CV",
                "XAXX010101000",
                "+1234567890",
                "test@company.com",
                true,
                now,
                now,
                null, null, null, null, null, null, null, null
        );

        testCompanyRequest = new CompanyRequest(
                "1",
                "Updated Company",
                2,
                "Nueva Razon Social",
                "XAXX010101000",
                "+9876543210",
                "updated@company.com",
                false,
                null, null, null, null, null, null, null, null
        );
    }

    @Nested
    @DisplayName("GET /api/companies")
    class GetAllCompaniesTests {

        @Test
        @DisplayName("getAllCompanies - should return paginated companies")
        void getAllCompanies_Paginated() throws Exception {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var companies = List.of(testCompanyResponse);
            var page = new PageImpl<>(companies, pageable, 1);

            when(companyService.getAllCompanies(any(Pageable.class), eq(null))).thenReturn(page);

            // Act & Assert
            mockMvc.perform(get("/api/companies")
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].companyName").value("Test Company"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12));
        }

        @Test
        @DisplayName("getAllCompanies - should filter by search term")
        void getAllCompanies_WithSearch() throws Exception {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var companies = List.of(testCompanyResponse);
            var page = new PageImpl<>(companies, pageable, 1);

            when(companyService.getAllCompanies(any(Pageable.class), eq("Test"))).thenReturn(page);

            // Act & Assert
            mockMvc.perform(get("/api/companies")
                            .param("page", "0")
                            .param("size", "12")
                            .param("search", "Test"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].companyName").value("Test Company"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("getAllCompanies - should return empty page when no results")
        void getAllCompanies_EmptyPage() throws Exception {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<CompanyResponse>(List.of(), pageable, 0);

            when(companyService.getAllCompanies(any(Pageable.class), eq(null))).thenReturn(page);

            // Act & Assert
            mockMvc.perform(get("/api/companies")
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));
        }

        @Test
        @DisplayName("getAllCompanies - should use default page size when not specified")
        void getAllCompanies_DefaultPageSize() throws Exception {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<>(List.of(testCompanyResponse), pageable, 1);

            when(companyService.getAllCompanies(any(Pageable.class), eq(null))).thenReturn(page);

            // Act & Assert
            mockMvc.perform(get("/api/companies"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(12));
        }
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
            CompanyRequest invalidRequest = new CompanyRequest(
                    "1",
                    null,  // companyName is required but missing
                    null,
                    null,
                    "XAXX010101000",
                    null,
                    null,
                    null,
                    null, null, null, null, null, null, null, null
            );

            // Act & Assert
            mockMvc.perform(put("/api/companies/{id}", testCompanyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("DELETE /api/companies/{id}")
    class DeleteCompanyTests {

        @Test
        @DisplayName("deleteCompany - should return 204 No Content on successful delete")
        void deleteCompany_Success() throws Exception {
            // Arrange
            doNothing().when(companyService).deleteCompany(testCompanyId);

            // Act & Assert
            mockMvc.perform(delete("/api/companies/{id}", testCompanyId))
                    .andExpect(status().isNoContent());
            verify(companyService).deleteCompany(testCompanyId);
        }

        @Test
        @DisplayName("deleteCompany - should return 404 Not Found when company not exists")
        void deleteCompany_NotFound() throws Exception {
            // Arrange
            doThrow(new CompanyNotFoundException(testCompanyId))
                    .when(companyService).deleteCompany(testCompanyId);

            // Act & Assert
            mockMvc.perform(delete("/api/companies/{id}", testCompanyId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Company not found with id: " + testCompanyId));
        }
    }
}