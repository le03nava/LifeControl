package com.lifecontrol.api.scheduling.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.scheduling.dto.SchedulingSlotResponse;
import com.lifecontrol.api.scheduling.exception.InvalidSchedulingSlotRangeException;
import com.lifecontrol.api.scheduling.exception.SchedulingActivityNotFoundException;
import com.lifecontrol.api.scheduling.service.SchedulingSlotService;
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
 * Controller-level verification of the slot read endpoint: the query parameters, the serialized wire
 * contract and the errors.
 *
 * <p>The {@code LocalDateTime} wire format is pinned here on purpose. This is the first response body
 * in the repo to carry a {@code LocalDateTime}, and no {@code spring.jackson.*} setting, no
 * {@code @JsonFormat} and no HTTP {@code ObjectMapper} bean exist to declare it, so the ISO-8601 shape
 * the frontend consumes is asserted rather than assumed.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SchedulingSlotController Tests")
class SchedulingSlotControllerTest {

    private static final String BASE_URL = "/api/scheduling/slots";
    private static final String FROM = "2026-09-28T00:00:00";
    private static final String TO = "2026-09-29T00:00:00";

    private MockMvc mockMvc;

    @Mock
    private SchedulingSlotService schedulingSlotService;

    @InjectMocks
    private SchedulingSlotController controller;

    private UUID activityId;
    private List<SchedulingSlotResponse> response;

    @BeforeEach
    void setUp() {
        // Standalone MockMvc builds its converter from plain Jackson defaults, which write
        // java.time values as arrays (LocalDateTime.of(2026,9,28,9,0) -> [2026,9,28,9,0]) unless
        // WRITE_DATES_AS_TIMESTAMPS is disabled. Spring Boot disables it, so production emits the ISO
        // strings asserted below; configure the converter the same way and let the integration test
        // exercise the real auto-configured ObjectMapper.
        var objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        activityId = UUID.randomUUID();
        response = List.of(new SchedulingSlotResponse(
                UUID.randomUUID(),
                activityId,
                LocalDateTime.of(2026, 9, 28, 9, 0),
                LocalDateTime.of(2026, 9, 28, 10, 0),
                4,
                0,
                4,
                "Available",
                true));
    }

    // ─── GET slots ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetSlotsTests {

        @Test
        @DisplayName("should return 200 and pin the ISO-8601 wire format of the slot")
        void returns200WithIsoWireFormat() throws Exception {
            when(schedulingSlotService.getSlots(eq(activityId), any(), any())).thenReturn(response);

            mockMvc.perform(get(BASE_URL)
                            .param("activityId", activityId.toString())
                            .param("from", FROM)
                            .param("to", TO))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].activityId").value(activityId.toString()))
                    // The frontend contract: LocalDateTime as "startAt":"2026-09-28T09:00:00", not a
                    // timestamp and not an array.
                    .andExpect(jsonPath("$[0].startAt").value("2026-09-28T09:00:00"))
                    .andExpect(jsonPath("$[0].endAt").value("2026-09-28T10:00:00"))
                    .andExpect(jsonPath("$[0].capacity").value(4))
                    .andExpect(jsonPath("$[0].booked").value(0))
                    .andExpect(jsonPath("$[0].available").value(4))
                    .andExpect(jsonPath("$[0].status").value("Available"))
                    .andExpect(jsonPath("$[0].enabled").value(true));
        }

        @Test
        @DisplayName("should return 400 with the domain message when the range is inverted")
        void returns400ForInvertedRange() throws Exception {
            when(schedulingSlotService.getSlots(eq(activityId), any(), any()))
                    .thenThrow(new InvalidSchedulingSlotRangeException("to must be after from"));

            mockMvc.perform(get(BASE_URL)
                            .param("activityId", activityId.toString())
                            .param("from", TO)
                            .param("to", FROM))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("to must be after from"));
        }

        @Test
        @DisplayName("should return 400 with the domain message when the span exceeds 90 days")
        void returns400ForTooWideRange() throws Exception {
            when(schedulingSlotService.getSlots(eq(activityId), any(), any()))
                    .thenThrow(new InvalidSchedulingSlotRangeException("the range must not exceed 90 days"));

            mockMvc.perform(get(BASE_URL)
                            .param("activityId", activityId.toString())
                            .param("from", "2026-01-01T00:00:00")
                            .param("to", "2026-06-01T00:00:00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("the range must not exceed 90 days"));
        }

        @Test
        @DisplayName("should return 404 when the activity is unknown")
        void returns404() throws Exception {
            when(schedulingSlotService.getSlots(eq(activityId), any(), any()))
                    .thenThrow(new SchedulingActivityNotFoundException(activityId));

            mockMvc.perform(get(BASE_URL)
                            .param("activityId", activityId.toString())
                            .param("from", FROM)
                            .param("to", TO))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Scheduling activity not found with id: " + activityId));
        }
    }
}
