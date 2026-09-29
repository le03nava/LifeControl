package com.lifecontrol.api.scheduling.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException;
import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentResponse;
import com.lifecontrol.api.scheduling.exception.InvalidSchedulingRangeException;
import com.lifecontrol.api.scheduling.exception.SchedulingAppointmentNotFoundException;
import com.lifecontrol.api.scheduling.exception.SchedulingSlotNotBookableException;
import com.lifecontrol.api.scheduling.exception.SchedulingSlotNotFoundException;
import com.lifecontrol.api.scheduling.service.SchedulingAppointmentService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller-level verification of the appointment endpoints: paths, the serialized response
 * contract, the booking 201, the 204 delete and the domain errors. The 400 travels through the real
 * {@link GlobalExceptionHandler}, so a bean-validation failure is asserted on the wire rather than
 * at mock level.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SchedulingAppointmentController Tests")
class SchedulingAppointmentControllerTest {

    private static final String BASE_URL = "/api/scheduling/appointments";

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private SchedulingAppointmentService schedulingAppointmentService;

    @InjectMocks
    private SchedulingAppointmentController controller;

    private UUID appointmentId;
    private UUID slotId;
    private SchedulingAppointmentResponse response;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        appointmentId = UUID.randomUUID();
        slotId = UUID.randomUUID();
        response = new SchedulingAppointmentResponse(
                appointmentId,
                slotId,
                LocalDateTime.of(2026, 9, 28, 9, 0),
                LocalDateTime.of(2026, 9, 28, 10, 0),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "employee-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Scheduled",
                "first visit",
                true,
                0L,
                LocalDateTime.of(2026, 9, 27, 12, 0),
                LocalDateTime.of(2026, 9, 27, 12, 0));
    }

    // ─── POST booking ───────────────────────────────────────────────────

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateAppointmentTests {

        @Test
        @DisplayName("should return 201 with the booked appointment")
        void returns201() throws Exception {
            when(schedulingAppointmentService.create(any(SchedulingAppointmentRequest.class)))
                    .thenReturn(response);

            mockMvc.perform(post(BASE_URL)
                            .contentType("application/json")
                            .content("{\"slotId\":\"" + slotId
                                    + "\",\"userId\":\"employee-1\",\"notes\":\"first visit\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(appointmentId.toString()))
                    .andExpect(jsonPath("$.slotId").value(slotId.toString()))
                    .andExpect(jsonPath("$.startAt").value("2026-09-28T09:00:00"))
                    .andExpect(jsonPath("$.endAt").value("2026-09-28T10:00:00"))
                    .andExpect(jsonPath("$.statusName").value("Scheduled"));
        }

        @Test
        @DisplayName("should return 400 through GlobalExceptionHandler when slotId is missing")
        void returns400WhenBodyInvalid() throws Exception {
            mockMvc.perform(post(BASE_URL).contentType("application/json").content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.slotId").exists());
        }

        @Test
        @DisplayName("should return 400 through GlobalExceptionHandler when the service rejects the status type")
        void returns400WhenServiceRejectsStatusType() throws Exception {
            when(schedulingAppointmentService.create(any(SchedulingAppointmentRequest.class)))
                    .thenThrow(new IllegalArgumentException("The provided status does not belong to type APPOINTMENT"));

            mockMvc.perform(post(BASE_URL).contentType("application/json").content("{\"slotId\":\"" + slotId + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("The provided status does not belong to type APPOINTMENT"));
        }

        @Test
        @DisplayName("should return 409 when the slot cannot be booked")
        void returns409WhenSlotNotBookable() throws Exception {
            when(schedulingAppointmentService.create(any(SchedulingAppointmentRequest.class)))
                    .thenThrow(SchedulingSlotNotBookableException.full(slotId, 1, 1));

            mockMvc.perform(post(BASE_URL).contentType("application/json").content("{\"slotId\":\"" + slotId + "\"}"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("should return 404 when the slot does not exist")
        void returns404WhenSlotMissing() throws Exception {
            when(schedulingAppointmentService.create(any(SchedulingAppointmentRequest.class)))
                    .thenThrow(new SchedulingSlotNotFoundException(slotId));

            mockMvc.perform(post(BASE_URL).contentType("application/json").content("{\"slotId\":\"" + slotId + "\"}"))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── GET ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET " + BASE_URL + "/{id}")
    class GetAppointmentTests {

        @Test
        @DisplayName("should return 200 with the appointment")
        void returns200() throws Exception {
            when(schedulingAppointmentService.getById(appointmentId)).thenReturn(response);

            mockMvc.perform(get(BASE_URL + "/" + appointmentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(appointmentId.toString()))
                    .andExpect(jsonPath("$.notes").value("first visit"));
        }

        @Test
        @DisplayName("should return 404 when the appointment does not exist")
        void returns404() throws Exception {
            when(schedulingAppointmentService.getById(appointmentId))
                    .thenThrow(new SchedulingAppointmentNotFoundException(appointmentId));

            mockMvc.perform(get(BASE_URL + "/" + appointmentId)).andExpect(status().isNotFound());
        }
    }

    // ─── GET list ────────────────────────────────────────────────

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAppointmentsTests {

        @Test
        @DisplayName("should return 200 and pin the ISO-8601 wire format of the filtered list")
        void returns200WithIsoWireFormat() throws Exception {
            var storeId = UUID.randomUUID();
            when(schedulingAppointmentService.getAppointments(eq(storeId), any(), any(), any()))
                    .thenReturn(List.of(response));

            mockMvc.perform(get(BASE_URL)
                            .param("storeId", storeId.toString())
                            .param("from", "2026-09-28T00:00:00")
                            .param("to", "2026-09-29T00:00:00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(appointmentId.toString()))
                    .andExpect(jsonPath("$[0].startAt").value("2026-09-28T09:00:00"))
                    .andExpect(jsonPath("$[0].endAt").value("2026-09-28T10:00:00"))
                    .andExpect(jsonPath("$[0].statusName").value("Scheduled"))
                    .andExpect(jsonPath("$[0].enabled").value(true));
        }

        @Test
        @DisplayName("should return 400 through GlobalExceptionHandler when the range is inverted")
        void returns400ForInvertedRange() throws Exception {
            var storeId = UUID.randomUUID();
            when(schedulingAppointmentService.getAppointments(eq(storeId), any(), any(), any()))
                    .thenThrow(new InvalidSchedulingRangeException("to must be after from"));

            mockMvc.perform(get(BASE_URL)
                            .param("storeId", storeId.toString())
                            .param("from", "2026-09-29T00:00:00")
                            .param("to", "2026-09-28T00:00:00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("to must be after from"));
        }

        @Test
        @DisplayName("should return 400 through GlobalExceptionHandler when a required parameter is missing")
        void returns400WhenRequiredParameterMissing() throws Exception {
            mockMvc.perform(get(BASE_URL)
                            .param("storeId", UUID.randomUUID().toString())
                            .param("from", "2026-09-28T00:00:00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Missing required parameter 'to'"));
        }
    }

    // ─── PUT reschedule ─────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class RescheduleTests {

        @Test
        @DisplayName("should return 200 with the moved appointment")
        void returns200() throws Exception {
            when(schedulingAppointmentService.reschedule(eq(appointmentId), any()))
                    .thenReturn(response);

            mockMvc.perform(put(BASE_URL + "/" + appointmentId)
                            .contentType("application/json")
                            .content("{\"slotId\":\"" + slotId + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slotId").value(slotId.toString()));
        }

        @Test
        @DisplayName("should return 409 when the appointment is terminal")
        void returns409() throws Exception {
            when(schedulingAppointmentService.reschedule(eq(appointmentId), any()))
                    .thenThrow(new InvalidStatusTransitionException("Cancelled", "Confirmed"));

            mockMvc.perform(put(BASE_URL + "/" + appointmentId)
                            .contentType("application/json")
                            .content("{\"slotId\":\"" + slotId + "\"}"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("should return 400 when the body omits slotId")
        void returns400() throws Exception {
            mockMvc.perform(put(BASE_URL + "/" + appointmentId)
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.slotId").exists());
        }
    }

    // ─── PATCH status ───────────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH " + BASE_URL + "/{id}/status")
    class UpdateStatusTests {

        @Test
        @DisplayName("should return 200 with the new status")
        void returns200() throws Exception {
            when(schedulingAppointmentService.updateStatus(eq(appointmentId), any()))
                    .thenReturn(response);

            mockMvc.perform(patch(BASE_URL + "/" + appointmentId + "/status")
                            .contentType("application/json")
                            .content("{\"statusId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusName").value("Scheduled"));
        }

        @Test
        @DisplayName("should return 409 for an invalid transition")
        void returns409() throws Exception {
            when(schedulingAppointmentService.updateStatus(eq(appointmentId), any()))
                    .thenThrow(new InvalidStatusTransitionException("Completed", "Confirmed"));

            mockMvc.perform(patch(BASE_URL + "/" + appointmentId + "/status")
                            .contentType("application/json")
                            .content("{\"statusId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("should return 400 when the body omits statusId")
        void returns400() throws Exception {
            mockMvc.perform(patch(BASE_URL + "/" + appointmentId + "/status")
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.statusId").exists());
        }
    }

    // ─── DELETE ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class DeleteTests {

        @Test
        @DisplayName("should return 204")
        void returns204() throws Exception {
            doNothing().when(schedulingAppointmentService).delete(appointmentId);

            mockMvc.perform(delete(BASE_URL + "/" + appointmentId)).andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when the appointment does not exist")
        void returns404() throws Exception {
            doThrow(new SchedulingAppointmentNotFoundException(appointmentId))
                    .when(schedulingAppointmentService)
                    .delete(appointmentId);

            mockMvc.perform(delete(BASE_URL + "/" + appointmentId)).andExpect(status().isNotFound());
        }
    }
}
