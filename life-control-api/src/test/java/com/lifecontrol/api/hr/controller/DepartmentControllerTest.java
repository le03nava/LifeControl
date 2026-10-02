package com.lifecontrol.api.hr.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.hr.dto.DepartmentRequest;
import com.lifecontrol.api.hr.dto.DepartmentResponse;
import com.lifecontrol.api.hr.exception.DepartmentNotFoundException;
import com.lifecontrol.api.hr.exception.DuplicateDepartmentException;
import com.lifecontrol.api.hr.service.DepartmentService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
@DisplayName("DepartmentController Tests")
class DepartmentControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private DepartmentService departmentService;

    @InjectMocks
    private DepartmentController departmentController;

    private UUID companyId;
    private UUID departmentId;
    private DepartmentResponse testResponse;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(departmentController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        companyId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
        testResponse = new DepartmentResponse(
                departmentId,
                companyId,
                "OPS",
                "Operations",
                "Operations department",
                1,
                true,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private DepartmentRequest buildRequest() {
        return new DepartmentRequest("OPS", "Operations", "Operations department", 1, true);
    }

    @Nested
    @DisplayName("GET /api/companies/{companyId}/departments")
    class GetAllDepartmentsTests {

        @Test
        @DisplayName("should return 200 with the company's departments when includeDisabled is true")
        void getAllDepartments_IncludeDisabled_Returns200() throws Exception {
            when(departmentService.getAllDepartments(companyId, true)).thenReturn(List.of(testResponse));

            mockMvc.perform(get("/api/companies/{companyId}/departments", companyId)
                            .param("includeDisabled", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$[0].departmentCode").value("OPS"))
                    .andExpect(jsonPath("$[0].departmentName").value("Operations"))
                    .andExpect(jsonPath("$[0].displayOrder").value(1))
                    .andExpect(jsonPath("$[0].enabled").value(true));
            verify(departmentService).getAllDepartments(companyId, true);
        }

        @Test
        @DisplayName("should default includeDisabled to false")
        void getAllDepartments_DefaultExcludesDisabled() throws Exception {
            when(departmentService.getAllDepartments(companyId, false)).thenReturn(List.of());

            mockMvc.perform(get("/api/companies/{companyId}/departments", companyId))
                    .andExpect(status().isOk());
            verify(departmentService).getAllDepartments(companyId, false);
        }
    }

    @Nested
    @DisplayName("GET /api/companies/{companyId}/departments/{id}")
    class GetDepartmentByIdTests {

        @Test
        @DisplayName("should return 200 when the department exists in the company")
        void getDepartmentById_Returns200() throws Exception {
            when(departmentService.getDepartmentById(companyId, departmentId)).thenReturn(testResponse);

            mockMvc.perform(get("/api/companies/{companyId}/departments/{id}", companyId, departmentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.departmentCode").value("OPS"));
        }

        @Test
        @DisplayName("should return 404 when the department does not exist in the company")
        void getDepartmentById_NotFound_Returns404() throws Exception {
            when(departmentService.getDepartmentById(companyId, departmentId))
                    .thenThrow(new DepartmentNotFoundException(departmentId));

            mockMvc.perform(get("/api/companies/{companyId}/departments/{id}", companyId, departmentId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/companies/{companyId}/departments")
    class CreateDepartmentTests {

        @Test
        @DisplayName("should return 201 when created successfully")
        void createDepartment_Returns201() throws Exception {
            when(departmentService.createDepartment(eq(companyId), any(DepartmentRequest.class)))
                    .thenReturn(testResponse);

            mockMvc.perform(post("/api/companies/{companyId}/departments", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.departmentCode").value("OPS"));
        }

        @Test
        @DisplayName("should return 400 when the code is blank")
        void createDepartment_BlankCode_Returns400() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/departments", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new DepartmentRequest("", "Operations", null, 1, true))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when the name is blank")
        void createDepartment_BlankName_Returns400() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/departments", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new DepartmentRequest("OPS", "", null, 1, true))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when displayOrder is not positive")
        void createDepartment_NonPositiveDisplayOrder_Returns400() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/departments", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new DepartmentRequest("OPS", "Operations", null, 0, true))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 409 when the code or name already exists in the company")
        void createDepartment_Duplicate_Returns409() throws Exception {
            when(departmentService.createDepartment(eq(companyId), any(DepartmentRequest.class)))
                    .thenThrow(new DuplicateDepartmentException("code", "OPS"));

            mockMvc.perform(post("/api/companies/{companyId}/departments", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("PUT /api/companies/{companyId}/departments/{id}")
    class UpdateDepartmentTests {

        @Test
        @DisplayName("should return 200 when updated successfully")
        void updateDepartment_Returns200() throws Exception {
            var updated = new DepartmentResponse(
                    departmentId, companyId, "FIN", "Finance", null, 2, true, LocalDateTime.now(), LocalDateTime.now());
            when(departmentService.updateDepartment(eq(companyId), eq(departmentId), any(DepartmentRequest.class)))
                    .thenReturn(updated);

            mockMvc.perform(put("/api/companies/{companyId}/departments/{id}", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new DepartmentRequest("FIN", "Finance", null, 2, true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.departmentCode").value("FIN"));
        }

        @Test
        @DisplayName("should return 400 when the name is blank")
        void updateDepartment_BlankName_Returns400() throws Exception {
            mockMvc.perform(put("/api/companies/{companyId}/departments/{id}", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new DepartmentRequest("OPS", "", null, 1, true))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when the department does not exist in the company")
        void updateDepartment_NotFound_Returns404() throws Exception {
            when(departmentService.updateDepartment(eq(companyId), eq(departmentId), any(DepartmentRequest.class)))
                    .thenThrow(new DepartmentNotFoundException(departmentId));

            mockMvc.perform(put("/api/companies/{companyId}/departments/{id}", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 409 when the code or name conflicts with another row")
        void updateDepartment_Duplicate_Returns409() throws Exception {
            when(departmentService.updateDepartment(eq(companyId), eq(departmentId), any(DepartmentRequest.class)))
                    .thenThrow(new DuplicateDepartmentException("name", "Operations"));

            mockMvc.perform(put("/api/companies/{companyId}/departments/{id}", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("DELETE /api/companies/{companyId}/departments/{id}")
    class DeleteDepartmentTests {

        @Test
        @DisplayName("should return 204 when soft-deleted successfully")
        void deleteDepartment_Returns204() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/departments/{id}", companyId, departmentId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when the department does not exist in the company")
        void deleteDepartment_NotFound_Returns404() throws Exception {
            doThrow(new DepartmentNotFoundException(departmentId))
                    .when(departmentService)
                    .deleteDepartment(companyId, departmentId);

            mockMvc.perform(delete("/api/companies/{companyId}/departments/{id}", companyId, departmentId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /api/companies/{companyId}/departments/{id}/enable")
    class SetDepartmentEnabledTests {

        @Test
        @DisplayName("should return 200 when enabled successfully")
        void setDepartmentEnabled_Returns200() throws Exception {
            when(departmentService.setDepartmentEnabled(companyId, departmentId, true))
                    .thenReturn(testResponse);

            mockMvc.perform(patch("/api/companies/{companyId}/departments/{id}/enable", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 200 when disabled successfully")
        void setDepartmentEnabled_Disable_Returns200() throws Exception {
            var disabled = new DepartmentResponse(
                    departmentId,
                    companyId,
                    "OPS",
                    "Operations",
                    null,
                    1,
                    false,
                    LocalDateTime.now(),
                    LocalDateTime.now());
            when(departmentService.setDepartmentEnabled(companyId, departmentId, false))
                    .thenReturn(disabled);

            mockMvc.perform(patch("/api/companies/{companyId}/departments/{id}/enable", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false));
        }

        @Test
        @DisplayName("should return 404 when the department does not exist in the company")
        void setDepartmentEnabled_NotFound_Returns404() throws Exception {
            when(departmentService.setDepartmentEnabled(companyId, departmentId, true))
                    .thenThrow(new DepartmentNotFoundException(departmentId));

            mockMvc.perform(patch("/api/companies/{companyId}/departments/{id}/enable", companyId, departmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                    .andExpect(status().isNotFound());
        }
    }
}
