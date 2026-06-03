package com.lifecontrol.api.status.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.status.dto.StatusTypeRequest;
import com.lifecontrol.api.status.dto.StatusTypeResponse;
import com.lifecontrol.api.status.exception.DuplicateStatusTypeException;
import com.lifecontrol.api.status.exception.StatusTypeNotFoundException;
import com.lifecontrol.api.status.service.StatusTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
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
@DisplayName("StatusTypeController Tests")
class StatusTypeControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private StatusTypeService statusTypeService;

    @InjectMocks
    private StatusTypeController statusTypeController;

    private StatusTypeResponse testStatusTypeResponse;
    private UUID testStatusTypeId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(statusTypeController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testStatusTypeId = UUID.randomUUID();
        testStatusTypeResponse = new StatusTypeResponse(
                testStatusTypeId, "ORDER", true,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    @Nested
    @DisplayName("GET /api/status-types")
    class GetAllStatusTypesTests {

        @Test
        @DisplayName("should return 200 with paginated list")
        void getAllStatusTypes_Returns200() throws Exception {
            var page = new PageImpl<>(List.of(testStatusTypeResponse), PageRequest.of(0, 12), 1);
            when(statusTypeService.getAllStatusTypes(any(), eq(null))).thenReturn(page);

            mockMvc.perform(get("/api/status-types"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].statusTypeName").value("ORDER"))
                    .andExpect(jsonPath("$.content[0].enabled").value(true));
        }

        @Test
        @DisplayName("should filter by search term")
        void getAllStatusTypes_WithSearch_Returns200() throws Exception {
            var page = new PageImpl<>(List.of(testStatusTypeResponse), PageRequest.of(0, 12), 1);
            when(statusTypeService.getAllStatusTypes(any(), eq("ORD"))).thenReturn(page);

            mockMvc.perform(get("/api/status-types?search=ORD"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].statusTypeName").value("ORDER"));
        }
    }

    @Nested
    @DisplayName("GET /api/status-types/{id}")
    class GetStatusTypeByIdTests {

        @Test
        @DisplayName("should return 200 when status type exists")
        void getStatusTypeById_Returns200() throws Exception {
            when(statusTypeService.getStatusTypeById(testStatusTypeId)).thenReturn(testStatusTypeResponse);

            mockMvc.perform(get("/api/status-types/{id}", testStatusTypeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusTypeName").value("ORDER"));
        }

        @Test
        @DisplayName("should return 404 when status type not found")
        void getStatusTypeById_NotFound_Returns404() throws Exception {
            when(statusTypeService.getStatusTypeById(testStatusTypeId))
                    .thenThrow(new StatusTypeNotFoundException(testStatusTypeId));

            mockMvc.perform(get("/api/status-types/{id}", testStatusTypeId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/status-types")
    class CreateStatusTypeTests {

        @Test
        @DisplayName("should return 201 when created successfully")
        void createStatusType_Returns201() throws Exception {
            StatusTypeRequest request = new StatusTypeRequest("ORDER", true);
            when(statusTypeService.createStatusType(any(StatusTypeRequest.class))).thenReturn(testStatusTypeResponse);

            mockMvc.perform(post("/api/status-types")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.statusTypeName").value("ORDER"));
        }

        @Test
        @DisplayName("should return 400 when statusTypeName is blank")
        void createStatusType_BlankName_Returns400() throws Exception {
            StatusTypeRequest invalidRequest = new StatusTypeRequest("", true);

            mockMvc.perform(post("/api/status-types")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 409 when statusTypeName already exists")
        void createStatusType_DuplicateName_Returns409() throws Exception {
            StatusTypeRequest request = new StatusTypeRequest("ORDER", true);
            when(statusTypeService.createStatusType(any(StatusTypeRequest.class)))
                    .thenThrow(new DuplicateStatusTypeException("ORDER"));

            mockMvc.perform(post("/api/status-types")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("PUT /api/status-types/{id}")
    class UpdateStatusTypeTests {

        @Test
        @DisplayName("should return 200 when updated successfully")
        void updateStatusType_Returns200() throws Exception {
            StatusTypeRequest request = new StatusTypeRequest("ORDER_V2", true);
            StatusTypeResponse updatedResponse = new StatusTypeResponse(
                    testStatusTypeId, "ORDER_V2", true,
                    LocalDateTime.now(), LocalDateTime.now());
            when(statusTypeService.updateStatusType(eq(testStatusTypeId), any(StatusTypeRequest.class)))
                    .thenReturn(updatedResponse);

            mockMvc.perform(put("/api/status-types/{id}", testStatusTypeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusTypeName").value("ORDER_V2"));
        }

        @Test
        @DisplayName("should return 404 when status type not found")
        void updateStatusType_NotFound_Returns404() throws Exception {
            StatusTypeRequest request = new StatusTypeRequest("ORDER", true);
            when(statusTypeService.updateStatusType(eq(testStatusTypeId), any(StatusTypeRequest.class)))
                    .thenThrow(new StatusTypeNotFoundException(testStatusTypeId));

            mockMvc.perform(put("/api/status-types/{id}", testStatusTypeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 400 when request is invalid")
        void updateStatusType_InvalidRequest_Returns400() throws Exception {
            StatusTypeRequest invalidRequest = new StatusTypeRequest("", true);

            mockMvc.perform(put("/api/status-types/{id}", testStatusTypeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("DELETE /api/status-types/{id}")
    class DeleteStatusTypeTests {

        @Test
        @DisplayName("should return 204 when deleted successfully")
        void deleteStatusType_Returns204() throws Exception {
            mockMvc.perform(delete("/api/status-types/{id}", testStatusTypeId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when status type not found")
        void deleteStatusType_NotFound_Returns404() throws Exception {
            doThrow(new StatusTypeNotFoundException(testStatusTypeId))
                    .when(statusTypeService).deleteStatusType(testStatusTypeId);

            mockMvc.perform(delete("/api/status-types/{id}", testStatusTypeId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /api/status-types/{id}/enable")
    class EnableStatusTypeTests {

        @Test
        @DisplayName("should return 200 when enabled successfully")
        void enableStatusType_Returns200() throws Exception {
            when(statusTypeService.enableStatusType(testStatusTypeId)).thenReturn(testStatusTypeResponse);

            mockMvc.perform(patch("/api/status-types/{id}/enable", testStatusTypeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusTypeName").value("ORDER"));
        }

        @Test
        @DisplayName("should return 404 when status type not found")
        void enableStatusType_NotFound_Returns404() throws Exception {
            when(statusTypeService.enableStatusType(testStatusTypeId))
                    .thenThrow(new StatusTypeNotFoundException(testStatusTypeId));

            mockMvc.perform(patch("/api/status-types/{id}/enable", testStatusTypeId))
                    .andExpect(status().isNotFound());
        }
    }
}
