package com.lifecontrol.api.shift.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.shift.dto.ShiftRequest;
import com.lifecontrol.api.shift.dto.ShiftResponse;
import com.lifecontrol.api.shift.exception.ShiftAlreadyOpenException;
import com.lifecontrol.api.shift.exception.ShiftNotFoundException;
import com.lifecontrol.api.shift.service.ShiftService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftController Tests")
class ShiftControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private ShiftService shiftService;

    @InjectMocks
    private ShiftController shiftController;

    private UUID shiftId;
    private UUID companyStoreId;
    private ShiftResponse testShiftResponse;
    private ShiftRequest testShiftRequest;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(shiftController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        shiftId = UUID.randomUUID();
        companyStoreId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testShiftResponse = new ShiftResponse(
                shiftId,
                companyStoreId,
                "user123",
                now.minusHours(2),
                null,
                "ABIERTO",
                true,
                now,
                now
        );

        testShiftRequest = new ShiftRequest(
                companyStoreId,
                "user123",
                true
        );
    }

    // ─────────────────────────────────────────────
    // GET /api/shifts
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/shifts")
    class GetAllShiftsTests {

        @Test
        @DisplayName("should return 200 with paginated shifts")
        void getAllShifts_Paginated_Returns200() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var shifts = List.of(testShiftResponse);
            var page = new PageImpl<>(shifts, pageable, 1);

            when(shiftService.getAllShifts(any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/shifts")
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].id").value(shiftId.toString()))
                    .andExpect(jsonPath("$.content[0].companyStoreId").value(companyStoreId.toString()))
                    .andExpect(jsonPath("$.content[0].userId").value("user123"))
                    .andExpect(jsonPath("$.content[0].status").value("ABIERTO"))
                    .andExpect(jsonPath("$.content[0].enabled").value(true))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12));
        }

        @Test
        @DisplayName("should return 200 with empty page when no shifts exist")
        void getAllShifts_EmptyPage_Returns200() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<ShiftResponse>(List.of(), pageable, 0);

            when(shiftService.getAllShifts(any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/shifts")
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/shifts/open
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/shifts/open")
    class GetOpenShiftsTests {

        @Test
        @DisplayName("should return 200 with list of open shifts")
        void getOpenShifts_Returns200() throws Exception {
            when(shiftService.getOpenShifts()).thenReturn(List.of(testShiftResponse));

            mockMvc.perform(get("/api/shifts/open"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].id").value(shiftId.toString()))
                    .andExpect(jsonPath("$[0].status").value("ABIERTO"))
                    .andExpect(jsonPath("$[0].companyStoreId").value(companyStoreId.toString()));
        }

        @Test
        @DisplayName("should return 200 with empty list when no open shifts")
        void getOpenShifts_EmptyList_Returns200() throws Exception {
            when(shiftService.getOpenShifts()).thenReturn(List.of());

            mockMvc.perform(get("/api/shifts/open"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/shifts/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/shifts/{id}")
    class GetShiftByIdTests {

        @Test
        @DisplayName("should return 200 with shift when found")
        void getShiftById_Found_Returns200() throws Exception {
            when(shiftService.getShiftById(shiftId)).thenReturn(testShiftResponse);

            mockMvc.perform(get("/api/shifts/{id}", shiftId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(shiftId.toString()))
                    .andExpect(jsonPath("$.companyStoreId").value(companyStoreId.toString()))
                    .andExpect(jsonPath("$.userId").value("user123"))
                    .andExpect(jsonPath("$.status").value("ABIERTO"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when shift not found")
        void getShiftById_NotFound_Returns404() throws Exception {
            when(shiftService.getShiftById(shiftId))
                    .thenThrow(new ShiftNotFoundException(shiftId));

            mockMvc.perform(get("/api/shifts/{id}", shiftId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Shift not found with id: " + shiftId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // POST /api/shifts/open
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/shifts/open")
    class OpenShiftTests {

        @Test
        @DisplayName("should return 201 with opened shift")
        void openShift_Success_Returns201() throws Exception {
            when(shiftService.openShift(eq(companyStoreId), eq("user123")))
                    .thenReturn(testShiftResponse);

            mockMvc.perform(post("/api/shifts/open")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testShiftRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(shiftId.toString()))
                    .andExpect(jsonPath("$.companyStoreId").value(companyStoreId.toString()))
                    .andExpect(jsonPath("$.userId").value("user123"))
                    .andExpect(jsonPath("$.status").value("ABIERTO"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 409 when an open shift already exists for the store")
        void openShift_DuplicateOpen_Returns409() throws Exception {
            when(shiftService.openShift(eq(companyStoreId), eq("user123")))
                    .thenThrow(new ShiftAlreadyOpenException(companyStoreId));

            mockMvc.perform(post("/api/shifts/open")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testShiftRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("An open shift already exists for store: " + companyStoreId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("should return 400 when required fields are missing")
        void openShift_MissingRequiredFields_Returns400() throws Exception {
            var invalidRequest = new ShiftRequest(null, null, null);

            mockMvc.perform(post("/api/shifts/open")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.companyStoreId").exists())
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // POST /api/shifts/{id}/close
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/shifts/{id}/close")
    class CloseShiftTests {

        @Test
        @DisplayName("should return 200 with closed shift")
        void closeShift_Success_Returns200() throws Exception {
            var now = LocalDateTime.now();
            var closedShift = new ShiftResponse(
                    shiftId, companyStoreId, "user123",
                    now.minusHours(3), now,
                    "CERRADO", true, now, now
            );

            when(shiftService.closeShift(shiftId)).thenReturn(closedShift);

            mockMvc.perform(post("/api/shifts/{id}/close", shiftId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(shiftId.toString()))
                    .andExpect(jsonPath("$.status").value("CERRADO"))
                    .andExpect(jsonPath("$.closedAt").isNotEmpty());
        }

        @Test
        @DisplayName("should return 404 when shift not found")
        void closeShift_NotFound_Returns404() throws Exception {
            when(shiftService.closeShift(shiftId))
                    .thenThrow(new ShiftNotFoundException(shiftId));

            mockMvc.perform(post("/api/shifts/{id}/close", shiftId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Shift not found with id: " + shiftId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // PUT /api/shifts/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PUT /api/shifts/{id}")
    class UpdateShiftTests {

        @Test
        @DisplayName("should return 200 with updated shift")
        void updateShift_Success_Returns200() throws Exception {
            when(shiftService.updateShift(eq(shiftId), any(ShiftRequest.class)))
                    .thenReturn(testShiftResponse);

            mockMvc.perform(put("/api/shifts/{id}", shiftId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testShiftRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(shiftId.toString()))
                    .andExpect(jsonPath("$.companyStoreId").value(companyStoreId.toString()));
        }

        @Test
        @DisplayName("should return 404 when shift not found")
        void updateShift_NotFound_Returns404() throws Exception {
            when(shiftService.updateShift(eq(shiftId), any(ShiftRequest.class)))
                    .thenThrow(new ShiftNotFoundException(shiftId));

            mockMvc.perform(put("/api/shifts/{id}", shiftId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testShiftRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Shift not found with id: " + shiftId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // DELETE /api/shifts/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("DELETE /api/shifts/{id}")
    class DeleteShiftTests {

        @Test
        @DisplayName("should return 204 on successful soft delete")
        void deleteShift_Success_Returns204() throws Exception {
            mockMvc.perform(delete("/api/shifts/{id}", shiftId))
                    .andExpect(status().isNoContent());

            verify(shiftService).deleteShift(shiftId);
        }

        @Test
        @DisplayName("should return 404 when shift not found")
        void deleteShift_NotFound_Returns404() throws Exception {
            doThrow(new ShiftNotFoundException(shiftId))
                    .when(shiftService).deleteShift(shiftId);

            mockMvc.perform(delete("/api/shifts/{id}", shiftId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Shift not found with id: " + shiftId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // PATCH /api/shifts/{id}/enable
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PATCH /api/shifts/{id}/enable")
    class EnableShiftTests {

        @Test
        @DisplayName("should return 200 with re-enabled shift")
        void enableShift_Success_Returns200() throws Exception {
            when(shiftService.enableShift(shiftId)).thenReturn(testShiftResponse);

            mockMvc.perform(patch("/api/shifts/{id}/enable", shiftId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(shiftId.toString()))
                    .andExpect(jsonPath("$.enabled").value(true));
        }
    }
}
