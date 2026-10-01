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
import com.lifecontrol.api.hr.dto.SeniorityLevelRequest;
import com.lifecontrol.api.hr.dto.SeniorityLevelResponse;
import com.lifecontrol.api.hr.exception.DuplicateSeniorityLevelException;
import com.lifecontrol.api.hr.exception.SeniorityLevelNotFoundException;
import com.lifecontrol.api.hr.service.SeniorityLevelService;
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
@DisplayName("SeniorityLevelController Tests")
class SeniorityLevelControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private SeniorityLevelService seniorityLevelService;

    @InjectMocks
    private SeniorityLevelController seniorityLevelController;

    private SeniorityLevelResponse testResponse;
    private UUID testId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(seniorityLevelController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testId = UUID.randomUUID();
        testResponse =
                new SeniorityLevelResponse(testId, "L1", "Junior", 1, true, LocalDateTime.now(), LocalDateTime.now());
    }

    @Nested
    @DisplayName("GET /api/seniority-levels")
    class GetAllSeniorityLevelsTests {

        @Test
        @DisplayName("should return 200 with every level when includeDisabled is true")
        void getAllSeniorityLevels_IncludeDisabled_Returns200() throws Exception {
            when(seniorityLevelService.getAllSeniorityLevels(true)).thenReturn(List.of(testResponse));

            mockMvc.perform(get("/api/seniority-levels").param("includeDisabled", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].levelCode").value("L1"))
                    .andExpect(jsonPath("$[0].levelName").value("Junior"))
                    .andExpect(jsonPath("$[0].rank").value(1))
                    .andExpect(jsonPath("$[0].enabled").value(true));
            verify(seniorityLevelService).getAllSeniorityLevels(true);
        }

        @Test
        @DisplayName("should default includeDisabled to false")
        void getAllSeniorityLevels_DefaultExcludesDisabled() throws Exception {
            when(seniorityLevelService.getAllSeniorityLevels(false)).thenReturn(List.of());

            mockMvc.perform(get("/api/seniority-levels")).andExpect(status().isOk());
            verify(seniorityLevelService).getAllSeniorityLevels(false);
        }
    }

    @Nested
    @DisplayName("GET /api/seniority-levels/{id}")
    class GetSeniorityLevelByIdTests {

        @Test
        @DisplayName("should return 200 when the level exists")
        void getSeniorityLevelById_Returns200() throws Exception {
            when(seniorityLevelService.getSeniorityLevelById(testId)).thenReturn(testResponse);

            mockMvc.perform(get("/api/seniority-levels/{id}", testId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.levelCode").value("L1"));
        }

        @Test
        @DisplayName("should return 404 when the level does not exist")
        void getSeniorityLevelById_NotFound_Returns404() throws Exception {
            when(seniorityLevelService.getSeniorityLevelById(testId))
                    .thenThrow(new SeniorityLevelNotFoundException(testId));

            mockMvc.perform(get("/api/seniority-levels/{id}", testId)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/seniority-levels")
    class CreateSeniorityLevelTests {

        @Test
        @DisplayName("should return 201 when created successfully")
        void createSeniorityLevel_Returns201() throws Exception {
            when(seniorityLevelService.createSeniorityLevel(any(SeniorityLevelRequest.class)))
                    .thenReturn(testResponse);

            mockMvc.perform(post("/api/seniority-levels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new SeniorityLevelRequest("L1", "Junior", 1, true))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.levelCode").value("L1"));
        }

        @Test
        @DisplayName("should return 400 when the code is blank")
        void createSeniorityLevel_BlankCode_Returns400() throws Exception {
            mockMvc.perform(post("/api/seniority-levels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new SeniorityLevelRequest("", "Junior", 1, true))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when rank is null")
        void createSeniorityLevel_NullRank_Returns400() throws Exception {
            mockMvc.perform(post("/api/seniority-levels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new SeniorityLevelRequest("L1", "Junior", null, true))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when rank is zero")
        void createSeniorityLevel_ZeroRank_Returns400() throws Exception {
            mockMvc.perform(post("/api/seniority-levels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new SeniorityLevelRequest("L1", "Junior", 0, true))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 409 when a natural key already exists")
        void createSeniorityLevel_Duplicate_Returns409() throws Exception {
            when(seniorityLevelService.createSeniorityLevel(any(SeniorityLevelRequest.class)))
                    .thenThrow(new DuplicateSeniorityLevelException("code", "L1"));

            mockMvc.perform(post("/api/seniority-levels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new SeniorityLevelRequest("L1", "Junior", 1, true))))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("PUT /api/seniority-levels/{id}")
    class UpdateSeniorityLevelTests {

        @Test
        @DisplayName("should return 200 when updated successfully")
        void updateSeniorityLevel_Returns200() throws Exception {
            var updated = new SeniorityLevelResponse(
                    testId, "L2", "Semi Senior", 2, true, LocalDateTime.now(), LocalDateTime.now());
            when(seniorityLevelService.updateSeniorityLevel(eq(testId), any(SeniorityLevelRequest.class)))
                    .thenReturn(updated);

            mockMvc.perform(put("/api/seniority-levels/{id}", testId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new SeniorityLevelRequest("L2", "Semi Senior", 2, true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rank").value(2));
        }

        @Test
        @DisplayName("should return 404 when the level does not exist")
        void updateSeniorityLevel_NotFound_Returns404() throws Exception {
            when(seniorityLevelService.updateSeniorityLevel(eq(testId), any(SeniorityLevelRequest.class)))
                    .thenThrow(new SeniorityLevelNotFoundException(testId));

            mockMvc.perform(put("/api/seniority-levels/{id}", testId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new SeniorityLevelRequest("L1", "Junior", 1, true))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 409 when a natural key conflicts with another level")
        void updateSeniorityLevel_Duplicate_Returns409() throws Exception {
            when(seniorityLevelService.updateSeniorityLevel(eq(testId), any(SeniorityLevelRequest.class)))
                    .thenThrow(new DuplicateSeniorityLevelException("rank", 1));

            mockMvc.perform(put("/api/seniority-levels/{id}", testId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new SeniorityLevelRequest("L1", "Junior", 1, true))))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("DELETE /api/seniority-levels/{id}")
    class DeleteSeniorityLevelTests {

        @Test
        @DisplayName("should return 204 when soft-deleted successfully")
        void deleteSeniorityLevel_Returns204() throws Exception {
            mockMvc.perform(delete("/api/seniority-levels/{id}", testId)).andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when the level does not exist")
        void deleteSeniorityLevel_NotFound_Returns404() throws Exception {
            doThrow(new SeniorityLevelNotFoundException(testId))
                    .when(seniorityLevelService)
                    .deleteSeniorityLevel(testId);

            mockMvc.perform(delete("/api/seniority-levels/{id}", testId)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /api/seniority-levels/{id}/enable")
    class SetSeniorityLevelEnabledTests {

        @Test
        @DisplayName("should return 200 when enabled successfully")
        void setSeniorityLevelEnabled_Returns200() throws Exception {
            when(seniorityLevelService.setSeniorityLevelEnabled(eq(testId), eq(true)))
                    .thenReturn(testResponse);

            mockMvc.perform(patch("/api/seniority-levels/{id}/enable", testId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 200 when disabled successfully")
        void setSeniorityLevelEnabled_Disable_Returns200() throws Exception {
            var disabled = new SeniorityLevelResponse(
                    testId, "L1", "Junior", 1, false, LocalDateTime.now(), LocalDateTime.now());
            when(seniorityLevelService.setSeniorityLevelEnabled(eq(testId), eq(false)))
                    .thenReturn(disabled);

            mockMvc.perform(patch("/api/seniority-levels/{id}/enable", testId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false));
        }

        @Test
        @DisplayName("should return 404 when the level does not exist")
        void setSeniorityLevelEnabled_NotFound_Returns404() throws Exception {
            when(seniorityLevelService.setSeniorityLevelEnabled(eq(testId), eq(true)))
                    .thenThrow(new SeniorityLevelNotFoundException(testId));

            mockMvc.perform(patch("/api/seniority-levels/{id}/enable", testId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                    .andExpect(status().isNotFound());
        }
    }
}
