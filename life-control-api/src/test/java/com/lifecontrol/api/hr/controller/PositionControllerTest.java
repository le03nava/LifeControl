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
import com.lifecontrol.api.hr.dto.PositionRequest;
import com.lifecontrol.api.hr.dto.PositionResponse;
import com.lifecontrol.api.hr.exception.DepartmentNotFoundException;
import com.lifecontrol.api.hr.exception.DuplicatePositionException;
import com.lifecontrol.api.hr.exception.PositionNotFoundException;
import com.lifecontrol.api.hr.service.PositionService;
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
@DisplayName("PositionController Tests")
class PositionControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private PositionService positionService;

    @InjectMocks
    private PositionController positionController;

    private UUID companyId;
    private UUID departmentId;
    private UUID positionId;
    private PositionResponse testResponse;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(positionController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        companyId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
        positionId = UUID.randomUUID();
        testResponse = new PositionResponse(
                positionId,
                companyId,
                departmentId,
                "OP1",
                "Operator",
                "Operator position",
                null,
                1,
                true,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private PositionRequest buildRequest() {
        return new PositionRequest(departmentId, "OP1", "Operator", "Operator position", null, 1, true);
    }

    @Nested
    @DisplayName("GET /api/companies/{companyId}/positions")
    class GetAllPositionsTests {

        @Test
        @DisplayName("should return 200 with the filtered positions")
        void getAllPositions_Filtered_Returns200() throws Exception {
            when(positionService.getAllPositions(companyId, departmentId, true)).thenReturn(List.of(testResponse));

            mockMvc.perform(get("/api/companies/{companyId}/positions", companyId)
                            .param("departmentId", departmentId.toString())
                            .param("includeDisabled", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].companyId").value(companyId.toString()))
                    .andExpect(jsonPath("$[0].departmentId").value(departmentId.toString()))
                    .andExpect(jsonPath("$[0].positionCode").value("OP1"))
                    .andExpect(jsonPath("$[0].positionName").value("Operator"))
                    .andExpect(jsonPath("$[0].enabled").value(true));
            verify(positionService).getAllPositions(companyId, departmentId, true);
        }

        @Test
        @DisplayName("should default departmentId to null and includeDisabled to false")
        void getAllPositions_Defaults() throws Exception {
            when(positionService.getAllPositions(companyId, null, false)).thenReturn(List.of());

            mockMvc.perform(get("/api/companies/{companyId}/positions", companyId))
                    .andExpect(status().isOk());
            verify(positionService).getAllPositions(companyId, null, false);
        }
    }

    @Nested
    @DisplayName("GET /api/companies/{companyId}/positions/{id}")
    class GetPositionByIdTests {

        @Test
        @DisplayName("should return 200 when the position exists in the company")
        void getPositionById_Returns200() throws Exception {
            when(positionService.getPositionById(companyId, positionId)).thenReturn(testResponse);

            mockMvc.perform(get("/api/companies/{companyId}/positions/{id}", companyId, positionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.positionCode").value("OP1"));
        }

        @Test
        @DisplayName("should return 404 when the position does not exist in the company")
        void getPositionById_NotFound_Returns404() throws Exception {
            when(positionService.getPositionById(companyId, positionId))
                    .thenThrow(new PositionNotFoundException(positionId));

            mockMvc.perform(get("/api/companies/{companyId}/positions/{id}", companyId, positionId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/companies/{companyId}/positions")
    class CreatePositionTests {

        @Test
        @DisplayName("should return 201 when created successfully")
        void createPosition_Returns201() throws Exception {
            when(positionService.createPosition(eq(companyId), any(PositionRequest.class)))
                    .thenReturn(testResponse);

            mockMvc.perform(post("/api/companies/{companyId}/positions", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.positionCode").value("OP1"));
        }

        @Test
        @DisplayName("should return 400 when the code is blank")
        void createPosition_BlankCode_Returns400() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/positions", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new PositionRequest(departmentId, "", "Operator", null, null, 1, true))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when the department is null")
        void createPosition_NullDepartment_Returns400() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/positions", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new PositionRequest(null, "OP1", "Operator", null, null, 1, true))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when displayOrder is not positive")
        void createPosition_NonPositiveDisplayOrder_Returns400() throws Exception {
            mockMvc.perform(post("/api/companies/{companyId}/positions", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new PositionRequest(departmentId, "OP1", "Operator", null, null, -1, true))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when the reporting parent belongs to another company")
        void createPosition_CrossCompanyParent_Returns400() throws Exception {
            when(positionService.createPosition(eq(companyId), any(PositionRequest.class)))
                    .thenThrow(new IllegalArgumentException(
                            "A position may only report to a position of the same company"));

            mockMvc.perform(post("/api/companies/{companyId}/positions", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when the reporting chain would close a cycle")
        void createPosition_Cycle_Returns400() throws Exception {
            when(positionService.createPosition(eq(companyId), any(PositionRequest.class)))
                    .thenThrow(new IllegalArgumentException(
                            "A position cannot report to itself or to one of its descendants"));

            mockMvc.perform(post("/api/companies/{companyId}/positions", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when the department belongs to another company")
        void createPosition_DepartmentNotFound_Returns404() throws Exception {
            when(positionService.createPosition(eq(companyId), any(PositionRequest.class)))
                    .thenThrow(new DepartmentNotFoundException(departmentId));

            mockMvc.perform(post("/api/companies/{companyId}/positions", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 409 when the code or name already exists in the department")
        void createPosition_Duplicate_Returns409() throws Exception {
            when(positionService.createPosition(eq(companyId), any(PositionRequest.class)))
                    .thenThrow(new DuplicatePositionException("code", "OP1"));

            mockMvc.perform(post("/api/companies/{companyId}/positions", companyId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("PUT /api/companies/{companyId}/positions/{id}")
    class UpdatePositionTests {

        @Test
        @DisplayName("should return 200 when updated successfully")
        void updatePosition_Returns200() throws Exception {
            var updated = new PositionResponse(
                    positionId,
                    companyId,
                    departmentId,
                    "OP2",
                    "Senior Operator",
                    null,
                    null,
                    2,
                    true,
                    LocalDateTime.now(),
                    LocalDateTime.now());
            when(positionService.updatePosition(eq(companyId), eq(positionId), any(PositionRequest.class)))
                    .thenReturn(updated);

            mockMvc.perform(put("/api/companies/{companyId}/positions/{id}", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new PositionRequest(departmentId, "OP2", "Senior Operator", null, null, 2, true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.positionCode").value("OP2"));
        }

        @Test
        @DisplayName("should return 400 when the code is blank")
        void updatePosition_BlankCode_Returns400() throws Exception {
            mockMvc.perform(put("/api/companies/{companyId}/positions/{id}", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new PositionRequest(departmentId, "", "Operator", null, null, 1, true))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when the reporting chain would close a cycle")
        void updatePosition_Cycle_Returns400() throws Exception {
            when(positionService.updatePosition(eq(companyId), eq(positionId), any(PositionRequest.class)))
                    .thenThrow(new IllegalArgumentException(
                            "A position cannot report to itself or to one of its descendants"));

            mockMvc.perform(put("/api/companies/{companyId}/positions/{id}", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when the position does not exist in the company")
        void updatePosition_NotFound_Returns404() throws Exception {
            when(positionService.updatePosition(eq(companyId), eq(positionId), any(PositionRequest.class)))
                    .thenThrow(new PositionNotFoundException(positionId));

            mockMvc.perform(put("/api/companies/{companyId}/positions/{id}", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 409 when the code or name conflicts with another row")
        void updatePosition_Duplicate_Returns409() throws Exception {
            when(positionService.updatePosition(eq(companyId), eq(positionId), any(PositionRequest.class)))
                    .thenThrow(new DuplicatePositionException("name", "Operator"));

            mockMvc.perform(put("/api/companies/{companyId}/positions/{id}", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildRequest())))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("DELETE /api/companies/{companyId}/positions/{id}")
    class DeletePositionTests {

        @Test
        @DisplayName("should return 204 when soft-deleted successfully")
        void deletePosition_Returns204() throws Exception {
            mockMvc.perform(delete("/api/companies/{companyId}/positions/{id}", companyId, positionId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when the position does not exist in the company")
        void deletePosition_NotFound_Returns404() throws Exception {
            doThrow(new PositionNotFoundException(positionId))
                    .when(positionService)
                    .deletePosition(companyId, positionId);

            mockMvc.perform(delete("/api/companies/{companyId}/positions/{id}", companyId, positionId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /api/companies/{companyId}/positions/{id}/enable")
    class SetPositionEnabledTests {

        @Test
        @DisplayName("should return 200 when enabled successfully")
        void setPositionEnabled_Returns200() throws Exception {
            when(positionService.setPositionEnabled(companyId, positionId, true))
                    .thenReturn(testResponse);

            mockMvc.perform(patch("/api/companies/{companyId}/positions/{id}/enable", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when the position does not exist in the company")
        void setPositionEnabled_NotFound_Returns404() throws Exception {
            when(positionService.setPositionEnabled(companyId, positionId, true))
                    .thenThrow(new PositionNotFoundException(positionId));

            mockMvc.perform(patch("/api/companies/{companyId}/positions/{id}/enable", companyId, positionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                    .andExpect(status().isNotFound());
        }
    }
}
