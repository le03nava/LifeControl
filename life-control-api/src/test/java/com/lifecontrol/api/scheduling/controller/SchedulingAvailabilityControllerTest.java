package com.lifecontrol.api.scheduling.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityResponse;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityWindowRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityWindowResponse;
import com.lifecontrol.api.scheduling.exception.InvalidSchedulingAvailabilityException;
import com.lifecontrol.api.scheduling.exception.SchedulingActivityNotFoundException;
import com.lifecontrol.api.scheduling.service.SchedulingAvailabilityService;
import java.time.LocalDate;
import java.time.LocalTime;
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
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller-level verification of the availability endpoints: paths, the serialized wire contract
 * and errors.
 *
 * <p>The wire format is pinned here on purpose. No {@code spring.jackson.*} setting, no
 * {@code @JsonFormat} and no HTTP {@code ObjectMapper} bean exist in this repo, and no other response
 * field carries a {@code LocalTime}/{\code LocalDate}, so this is the only place the frontend's
 * contract — an ISO-8601 string for both — is asserted rather than assumed.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SchedulingAvailabilityController Tests")
class SchedulingAvailabilityControllerTest {

    private static final String BASE_URL = "/api/scheduling/activities";

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private SchedulingAvailabilityService schedulingAvailabilityService;

    @InjectMocks
    private SchedulingAvailabilityController controller;

    private UUID activityId;
    private SchedulingAvailabilityResponse response;

    @BeforeEach
    void setUp() {
        // Standalone MockMvc builds its converter from plain Jackson defaults, which write
        // java.time values as arrays (LocalTime.of(9,0) -> [9,0]). Spring Boot disables
        // WRITE_DATES_AS_TIMESTAMPS, so production emits the ISO strings asserted below. Configure
        // the converter the same way here; the full-context integration test exercises the real
        // auto-configured ObjectMapper end to end.
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        activityId = UUID.randomUUID();
        response = new SchedulingAvailabilityResponse(
                activityId,
                List.of(new SchedulingAvailabilityWindowResponse(
                        UUID.randomUUID(),
                        1,
                        LocalTime.of(9, 0),
                        LocalTime.of(13, 0),
                        LocalDate.of(2026, 9, 28),
                        LocalDate.of(2026, 12, 31))));
    }

    private SchedulingAvailabilityRequest validRequest() {
        return new SchedulingAvailabilityRequest(List.of(new SchedulingAvailabilityWindowRequest(
                1, LocalTime.of(9, 0), LocalTime.of(13, 0), LocalDate.of(2026, 9, 28), LocalDate.of(2026, 12, 31))));
    }

    // ─── GET availability ───────────────────────────────────────────────

    @Nested
    @DisplayName("GET " + BASE_URL + "/{activityId}/availability")
    class GetAvailabilityTests {

        @Test
        @DisplayName("should return 200 and pin the ISO-8601 wire format of the window")
        void returns200WithIsoWireFormat() throws Exception {
            when(schedulingAvailabilityService.getAvailability(activityId)).thenReturn(response);

            mockMvc.perform(get(BASE_URL + "/{activityId}/availability", activityId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.activityId").value(activityId.toString()))
                    .andExpect(jsonPath("$.windows[0].dayOfWeek").value(1))
                    // The frontend contract: LocalTime as "startTime":"09:00:00", LocalDate as
                    // "validFrom":"2026-09-28", not timestamps and not arrays.
                    .andExpect(jsonPath("$.windows[0].startTime").value("09:00:00"))
                    .andExpect(jsonPath("$.windows[0].endTime").value("13:00:00"))
                    .andExpect(jsonPath("$.windows[0].validFrom").value("2026-09-28"))
                    .andExpect(jsonPath("$.windows[0].validTo").value("2026-12-31"));
        }

        @Test
        @DisplayName("should return 404 when the activity is unknown")
        void returns404() throws Exception {
            when(schedulingAvailabilityService.getAvailability(activityId))
                    .thenThrow(new SchedulingActivityNotFoundException(activityId));

            mockMvc.perform(get(BASE_URL + "/{activityId}/availability", activityId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Scheduling activity not found with id: " + activityId));
        }
    }

    // ─── PUT availability ───────────────────────────────────────────────

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{activityId}/availability")
    class ReplaceAvailabilityTests {

        @Test
        @DisplayName("should return 200 with the stored set")
        void returns200() throws Exception {
            when(schedulingAvailabilityService.replaceAvailability(
                            eq(activityId), any(SchedulingAvailabilityRequest.class)))
                    .thenReturn(response);

            mockMvc.perform(put(BASE_URL + "/{activityId}/availability", activityId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.activityId").value(activityId.toString()))
                    .andExpect(jsonPath("$.windows[0].startTime").value("09:00:00"))
                    .andExpect(jsonPath("$.windows[0].validFrom").value("2026-09-28"));
        }

        @Test
        @DisplayName("should return 400 when windows is missing")
        void returns400WhenWindowsMissing() throws Exception {
            mockMvc.perform(put(BASE_URL + "/{activityId}/availability", activityId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.windows").exists());
        }

        @Test
        @DisplayName("should return 400 when a window carries an out-of-range dayOfWeek")
        void returns400WhenDayOutOfRange() throws Exception {
            // Both ends of the ISO 1..7 range: 0 is below @Min(1) and 8 is above @Max(7).
            for (var outOfRangeDay : List.of(0, 8)) {
                var body = """
                        {"windows":[{"dayOfWeek":%d,"startTime":"09:00:00","endTime":"13:00:00",
                        "validFrom":"2026-09-28","validTo":"2026-12-31"}]}
                        """.formatted(outOfRangeDay);

                mockMvc.perform(put(BASE_URL + "/{activityId}/availability", activityId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errors['windows[0].dayOfWeek']").exists());
            }
        }

        @Test
        @DisplayName("should return 400 with the domain message when the service rejects the window set")
        void returns400WhenServiceRejectsTheSet() throws Exception {
            when(schedulingAvailabilityService.replaceAvailability(
                            eq(activityId), any(SchedulingAvailabilityRequest.class)))
                    .thenThrow(
                            new InvalidSchedulingAvailabilityException("availability windows overlap on dayOfWeek 1"));

            mockMvc.perform(put(BASE_URL + "/{activityId}/availability", activityId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("availability windows overlap on dayOfWeek 1"));
        }

        @Test
        @DisplayName("should return 404 when the activity is unknown")
        void returns404() throws Exception {
            when(schedulingAvailabilityService.replaceAvailability(
                            eq(activityId), any(SchedulingAvailabilityRequest.class)))
                    .thenThrow(new SchedulingActivityNotFoundException(activityId));

            mockMvc.perform(put(BASE_URL + "/{activityId}/availability", activityId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Scheduling activity not found with id: " + activityId));
        }
    }
}
