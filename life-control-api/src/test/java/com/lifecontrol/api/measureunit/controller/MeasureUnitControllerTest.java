package com.lifecontrol.api.measureunit.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.measureunit.dto.MeasureUnitRequest;
import com.lifecontrol.api.measureunit.dto.MeasureUnitResponse;
import com.lifecontrol.api.measureunit.exception.DuplicateMeasureUnitException;
import com.lifecontrol.api.measureunit.exception.MeasureUnitNotFoundException;
import com.lifecontrol.api.measureunit.service.MeasureUnitService;
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
@DisplayName("MeasureUnitController Tests")
class MeasureUnitControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private MeasureUnitService measureUnitService;

    @InjectMocks
    private MeasureUnitController measureUnitController;

    private MeasureUnitResponse testResponse;
    private UUID testId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(measureUnitController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        testId = UUID.randomUUID();
        testResponse = new MeasureUnitResponse(
                testId,
                "Kilogramo",
                "Kg",
                "PRODUCT",
                "KGM",
                "Para alimentos, materiales a granel, insumos industriales.",
                true,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    @Nested
    @DisplayName("GET /api/measure-units")
    class GetAllMeasureUnitsTests {

        @Test
        @DisplayName("should return 200 with list of enabled units")
        void getAllMeasureUnits_Default_Returns200() throws Exception {
            when(measureUnitService.getAllMeasureUnits(false)).thenReturn(List.of(testResponse));

            mockMvc.perform(get("/api/measure-units"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].measureUnitName").value("Kilogramo"))
                    .andExpect(jsonPath("$[0].satCode").value("KGM"));
        }

        @Test
        @DisplayName("should include disabled units when ?includeDisabled=true")
        void getAllMeasureUnits_IncludeDisabled_ReturnsAll() throws Exception {
            when(measureUnitService.getAllMeasureUnits(true)).thenReturn(List.of(testResponse));

            mockMvc.perform(get("/api/measure-units?includeDisabled=true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].measureUnitName").value("Kilogramo"));
        }
    }

    @Nested
    @DisplayName("GET /api/measure-units/{id}")
    class GetMeasureUnitByIdTests {

        @Test
        @DisplayName("should return 200 when measure unit exists")
        void getMeasureUnitById_Returns200() throws Exception {
            when(measureUnitService.getMeasureUnitById(testId)).thenReturn(testResponse);

            mockMvc.perform(get("/api/measure-units/{id}", testId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.measureUnitName").value("Kilogramo"))
                    .andExpect(jsonPath("$.satCode").value("KGM"));
        }

        @Test
        @DisplayName("should return 404 when measure unit not found")
        void getMeasureUnitById_NotFound_Returns404() throws Exception {
            when(measureUnitService.getMeasureUnitById(testId))
                    .thenThrow(new MeasureUnitNotFoundException(testId));

            mockMvc.perform(get("/api/measure-units/{id}", testId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/measure-units/type/{unitType}")
    class GetMeasureUnitsByTypeTests {

        @Test
        @DisplayName("should return 200 with units of valid type PRODUCT")
        void getMeasureUnitsByType_ValidType_Returns200() throws Exception {
            when(measureUnitService.getMeasureUnitsByType("PRODUCT")).thenReturn(List.of(testResponse));

            mockMvc.perform(get("/api/measure-units/type/PRODUCT"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].unitType").value("PRODUCT"))
                    .andExpect(jsonPath("$[0].measureUnitName").value("Kilogramo"));
        }

        @Test
        @DisplayName("should return 400 when unit type is invalid")
        void getMeasureUnitsByType_InvalidType_Returns400() throws Exception {
            when(measureUnitService.getMeasureUnitsByType("INVALID"))
                    .thenThrow(new IllegalArgumentException(
                            "No enum constant com.lifecontrol.api.measureunit.model.MeasureUnitType.INVALID"));

            mockMvc.perform(get("/api/measure-units/type/INVALID"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/measure-units")
    class CreateMeasureUnitTests {

        @Test
        @DisplayName("should return 201 when created successfully")
        void createMeasureUnit_Returns201() throws Exception {
            var request = new MeasureUnitRequest("Kilogramo", "Kg", "PRODUCT", "KGM", "Description");
            when(measureUnitService.createMeasureUnit(any(MeasureUnitRequest.class))).thenReturn(testResponse);

            mockMvc.perform(post("/api/measure-units")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.measureUnitName").value("Kilogramo"))
                    .andExpect(jsonPath("$.satCode").value("KGM"));
        }

        @Test
        @DisplayName("should return 400 when validation fails")
        void createMeasureUnit_ValidationError_Returns400() throws Exception {
            var invalidRequest = new MeasureUnitRequest("", "", "", "", "");

            mockMvc.perform(post("/api/measure-units")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 409 when SAT code already exists")
        void createMeasureUnit_DuplicateSatCode_Returns409() throws Exception {
            var request = new MeasureUnitRequest("Kilogramo", "Kg", "PRODUCT", "KGM", "Description");
            when(measureUnitService.createMeasureUnit(any(MeasureUnitRequest.class)))
                    .thenThrow(new DuplicateMeasureUnitException(
                            "Ya existe una unidad de medida con código SAT: KGM"));

            mockMvc.perform(post("/api/measure-units")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("PUT /api/measure-units/{id}")
    class UpdateMeasureUnitTests {

        @Test
        @DisplayName("should return 200 when updated successfully")
        void updateMeasureUnit_Returns200() throws Exception {
            var request = new MeasureUnitRequest("Kilogramo actualizado", "Kg", "PRODUCT", "KGM", "Updated description");
            var updatedResponse = new MeasureUnitResponse(
                    testId, "Kilogramo actualizado", "Kg", "PRODUCT", "KGM",
                    "Updated description", true,
                    LocalDateTime.now(), LocalDateTime.now());
            when(measureUnitService.updateMeasureUnit(eq(testId), any(MeasureUnitRequest.class)))
                    .thenReturn(updatedResponse);

            mockMvc.perform(put("/api/measure-units/{id}", testId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.measureUnitName").value("Kilogramo actualizado"));
        }

        @Test
        @DisplayName("should return 404 when measure unit not found")
        void updateMeasureUnit_NotFound_Returns404() throws Exception {
            var request = new MeasureUnitRequest("Kilogramo", "Kg", "PRODUCT", "KGM", "Description");
            when(measureUnitService.updateMeasureUnit(eq(testId), any(MeasureUnitRequest.class)))
                    .thenThrow(new MeasureUnitNotFoundException(testId));

            mockMvc.perform(put("/api/measure-units/{id}", testId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 409 when SAT code is already in use by another unit")
        void updateMeasureUnit_DuplicateSatCode_Returns409() throws Exception {
            var request = new MeasureUnitRequest("Kilogramo", "Kg", "PRODUCT", "KGM", "Description");
            when(measureUnitService.updateMeasureUnit(eq(testId), any(MeasureUnitRequest.class)))
                    .thenThrow(new DuplicateMeasureUnitException(
                            "Ya existe una unidad de medida con código SAT: KGM"));

            mockMvc.perform(put("/api/measure-units/{id}", testId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("DELETE /api/measure-units/{id}")
    class DeleteMeasureUnitTests {

        @Test
        @DisplayName("should return 204 when deleted successfully")
        void deleteMeasureUnit_Returns204() throws Exception {
            mockMvc.perform(delete("/api/measure-units/{id}", testId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when measure unit not found")
        void deleteMeasureUnit_NotFound_Returns404() throws Exception {
            doThrow(new MeasureUnitNotFoundException(testId))
                    .when(measureUnitService).deleteMeasureUnit(testId);

            mockMvc.perform(delete("/api/measure-units/{id}", testId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /api/measure-units/{id}/enable")
    class EnableMeasureUnitTests {

        @Test
        @DisplayName("should return 200 when enabled successfully")
        void enableMeasureUnit_Returns200() throws Exception {
            when(measureUnitService.enableMeasureUnit(testId)).thenReturn(testResponse);

            mockMvc.perform(patch("/api/measure-units/{id}/enable", testId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.measureUnitName").value("Kilogramo"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when measure unit not found")
        void enableMeasureUnit_NotFound_Returns404() throws Exception {
            when(measureUnitService.enableMeasureUnit(testId))
                    .thenThrow(new MeasureUnitNotFoundException(testId));

            mockMvc.perform(patch("/api/measure-units/{id}/enable", testId))
                    .andExpect(status().isNotFound());
        }
    }
}
