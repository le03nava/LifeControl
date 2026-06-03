package com.lifecontrol.api.status.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.status.dto.StatusRequest;
import com.lifecontrol.api.status.dto.StatusResponse;
import com.lifecontrol.api.status.exception.DuplicateStatusException;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.exception.StatusTypeNotFoundException;
import com.lifecontrol.api.status.service.StatusService;
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
@DisplayName("StatusController Tests")
class StatusControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private StatusService statusService;

    @InjectMocks
    private StatusController statusController;

    private StatusResponse testStatusResponse;
    private UUID testStatusId;
    private UUID testStatusTypeId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(statusController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testStatusId = UUID.randomUUID();
        testStatusTypeId = UUID.randomUUID();
        testStatusResponse = new StatusResponse(
                testStatusId, "PENDING", testStatusTypeId, "ORDER",
                true, LocalDateTime.now(), LocalDateTime.now()
        );
    }

    @Nested
    @DisplayName("GET /api/statuses")
    class GetStatusesByTypeIdTests {

        @Test
        @DisplayName("should return 200 with statuses for given type")
        void getStatusesByTypeId_Returns200() throws Exception {
            when(statusService.getStatusesByTypeId(testStatusTypeId)).thenReturn(List.of(testStatusResponse));

            mockMvc.perform(get("/api/statuses?statusTypeId={id}", testStatusTypeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].statusName").value("PENDING"))
                    .andExpect(jsonPath("$[0].statusTypeName").value("ORDER"));
        }

        @Test
        @DisplayName("should return 400 when statusTypeId is missing")
        void getStatusesByTypeId_MissingParam_Returns400() throws Exception {
            mockMvc.perform(get("/api/statuses"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when status type not found")
        void getStatusesByTypeId_TypeNotFound_Returns404() throws Exception {
            when(statusService.getStatusesByTypeId(testStatusTypeId))
                    .thenThrow(new StatusTypeNotFoundException(testStatusTypeId));

            mockMvc.perform(get("/api/statuses?statusTypeId={id}", testStatusTypeId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/statuses/{id}")
    class GetStatusByIdTests {

        @Test
        @DisplayName("should return 200 when status exists")
        void getStatusById_Returns200() throws Exception {
            when(statusService.getStatusById(testStatusId)).thenReturn(testStatusResponse);

            mockMvc.perform(get("/api/statuses/{id}", testStatusId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("PENDING"));
        }

        @Test
        @DisplayName("should return 200 when scoped by statusTypeId")
        void getStatusById_WithTypeId_Returns200() throws Exception {
            when(statusService.getStatusByIdAndTypeId(testStatusId, testStatusTypeId))
                    .thenReturn(testStatusResponse);

            mockMvc.perform(get("/api/statuses/{id}?statusTypeId={typeId}", testStatusId, testStatusTypeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("PENDING"));
        }

        @Test
        @DisplayName("should return 404 when status not found")
        void getStatusById_NotFound_Returns404() throws Exception {
            when(statusService.getStatusById(testStatusId))
                    .thenThrow(new StatusNotFoundException(testStatusId));

            mockMvc.perform(get("/api/statuses/{id}", testStatusId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/statuses")
    class CreateStatusTests {

        @Test
        @DisplayName("should return 201 when created successfully")
        void createStatus_Returns201() throws Exception {
            StatusRequest request = new StatusRequest("PENDING", testStatusTypeId, true);
            when(statusService.createStatus(any(StatusRequest.class))).thenReturn(testStatusResponse);

            mockMvc.perform(post("/api/statuses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.statusName").value("PENDING"));
        }

        @Test
        @DisplayName("should return 400 when statusName is blank")
        void createStatus_BlankName_Returns400() throws Exception {
            StatusRequest invalidRequest = new StatusRequest("", testStatusTypeId, true);

            mockMvc.perform(post("/api/statuses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when statusTypeId is null")
        void createStatus_NullTypeId_Returns400() throws Exception {
            StatusRequest invalidRequest = new StatusRequest("PENDING", null, true);

            mockMvc.perform(post("/api/statuses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 404 when status type not found")
        void createStatus_TypeNotFound_Returns404() throws Exception {
            StatusRequest request = new StatusRequest("PENDING", testStatusTypeId, true);
            when(statusService.createStatus(any(StatusRequest.class)))
                    .thenThrow(new StatusTypeNotFoundException(testStatusTypeId));

            mockMvc.perform(post("/api/statuses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 409 when duplicate name within type")
        void createStatus_DuplicateName_Returns409() throws Exception {
            StatusRequest request = new StatusRequest("PENDING", testStatusTypeId, true);
            when(statusService.createStatus(any(StatusRequest.class)))
                    .thenThrow(new DuplicateStatusException("PENDING"));

            mockMvc.perform(post("/api/statuses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("PUT /api/statuses/{id}")
    class UpdateStatusTests {

        @Test
        @DisplayName("should return 200 when updated successfully")
        void updateStatus_Returns200() throws Exception {
            StatusRequest request = new StatusRequest("WAITING", testStatusTypeId, true);
            StatusResponse updatedResponse = new StatusResponse(
                    testStatusId, "WAITING", testStatusTypeId, "ORDER",
                    true, LocalDateTime.now(), LocalDateTime.now());
            when(statusService.updateStatus(eq(testStatusId), any(StatusRequest.class))).thenReturn(updatedResponse);

            mockMvc.perform(put("/api/statuses/{id}", testStatusId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("WAITING"));
        }

        @Test
        @DisplayName("should return 404 when status not found")
        void updateStatus_NotFound_Returns404() throws Exception {
            StatusRequest request = new StatusRequest("PENDING", testStatusTypeId, true);
            when(statusService.updateStatus(eq(testStatusId), any(StatusRequest.class)))
                    .thenThrow(new StatusNotFoundException(testStatusId));

            mockMvc.perform(put("/api/statuses/{id}", testStatusId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /api/statuses/{id}")
    class DeleteStatusTests {

        @Test
        @DisplayName("should return 204 when deleted successfully")
        void deleteStatus_Returns204() throws Exception {
            mockMvc.perform(delete("/api/statuses/{id}", testStatusId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when status not found")
        void deleteStatus_NotFound_Returns404() throws Exception {
            doThrow(new StatusNotFoundException(testStatusId))
                    .when(statusService).deleteStatus(testStatusId);

            mockMvc.perform(delete("/api/statuses/{id}", testStatusId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /api/statuses/{id}/enable")
    class EnableStatusTests {

        @Test
        @DisplayName("should return 200 when enabled successfully")
        void enableStatus_Returns200() throws Exception {
            when(statusService.enableStatus(testStatusId)).thenReturn(testStatusResponse);

            mockMvc.perform(patch("/api/statuses/{id}/enable", testStatusId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("PENDING"));
        }

        @Test
        @DisplayName("should return 404 when status not found")
        void enableStatus_NotFound_Returns404() throws Exception {
            when(statusService.enableStatus(testStatusId))
                    .thenThrow(new StatusNotFoundException(testStatusId));

            mockMvc.perform(patch("/api/statuses/{id}/enable", testStatusId))
                    .andExpect(status().isNotFound());
        }
    }
}
