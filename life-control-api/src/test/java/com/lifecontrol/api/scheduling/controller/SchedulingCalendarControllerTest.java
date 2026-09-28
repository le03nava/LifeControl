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
import com.lifecontrol.api.scheduling.dto.SchedulingCalendarAppointmentResponse;
import com.lifecontrol.api.scheduling.dto.SchedulingCalendarSlotResponse;
import com.lifecontrol.api.scheduling.exception.InvalidSchedulingRangeException;
import com.lifecontrol.api.scheduling.service.SchedulingCalendarService;
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
 * Controller-level verification of the calendar projection endpoint: the query parameters, the
 * serialized wire contract and the errors. The {@code LocalDateTime} ISO-8601 shape is pinned here
 * the way {@code SchedulingSlotControllerTest} pins it, because no {@code spring.jackson.*} setting
 * or {@code @JsonFormat} declares it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SchedulingCalendarController Tests")
class SchedulingCalendarControllerTest {

    private static final String BASE_URL = "/api/scheduling/calendar";
    private static final String FROM = "2026-09-28T00:00:00";
    private static final String TO = "2026-09-29T00:00:00";

    private MockMvc mockMvc;

    @Mock
    private SchedulingCalendarService schedulingCalendarService;

    @InjectMocks
    private SchedulingCalendarController controller;

    private UUID storeId;
    private List<SchedulingCalendarSlotResponse> response;

    @BeforeEach
    void setUp() {
        var objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        storeId = UUID.randomUUID();
        response = List.of(new SchedulingCalendarSlotResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Yoga",
                true,
                LocalDateTime.of(2026, 9, 28, 9, 0),
                LocalDateTime.of(2026, 9, 28, 10, 0),
                4,
                1,
                3,
                "Available",
                List.of(new SchedulingCalendarAppointmentResponse(
                        UUID.randomUUID(),
                        "employee-1",
                        UUID.randomUUID(),
                        "Ada",
                        UUID.randomUUID(),
                        "Scheduled",
                        "first visit",
                        false))));
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetCalendarTests {

        @Test
        @DisplayName("should return 200 and pin the ISO-8601 wire format of the slot and its appointments")
        void returns200WithIsoWireFormat() throws Exception {
            when(schedulingCalendarService.getCalendar(eq(storeId), any(), any(), any(), any()))
                    .thenReturn(response);

            mockMvc.perform(get(BASE_URL)
                            .param("storeId", storeId.toString())
                            .param("from", FROM)
                            .param("to", TO))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].activityName").value("Yoga"))
                    .andExpect(jsonPath("$[0].activityEnabled").value(true))
                    .andExpect(jsonPath("$[0].startAt").value("2026-09-28T09:00:00"))
                    .andExpect(jsonPath("$[0].endAt").value("2026-09-28T10:00:00"))
                    .andExpect(jsonPath("$[0].capacity").value(4))
                    .andExpect(jsonPath("$[0].booked").value(1))
                    .andExpect(jsonPath("$[0].available").value(3))
                    .andExpect(jsonPath("$[0].status").value("Available"))
                    .andExpect(jsonPath("$[0].appointments[0].userId").value("employee-1"))
                    .andExpect(jsonPath("$[0].appointments[0].customerName").value("Ada"))
                    .andExpect(jsonPath("$[0].appointments[0].statusName").value("Scheduled"))
                    .andExpect(jsonPath("$[0].appointments[0].enabled").value(false));
        }

        @Test
        @DisplayName("should pass the optional userId and activityId through when present")
        void forwardsOptionalFilters() throws Exception {
            var userId = "employee-1";
            var activityId = UUID.randomUUID();
            when(schedulingCalendarService.getCalendar(eq(storeId), any(), any(), eq(userId), eq(activityId)))
                    .thenReturn(response);

            mockMvc.perform(get(BASE_URL)
                            .param("storeId", storeId.toString())
                            .param("from", FROM)
                            .param("to", TO)
                            .param("userId", userId)
                            .param("activityId", activityId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 400 with the domain message when the range is inverted")
        void returns400ForInvertedRange() throws Exception {
            when(schedulingCalendarService.getCalendar(eq(storeId), any(), any(), any(), any()))
                    .thenThrow(new InvalidSchedulingRangeException("to must be after from"));

            mockMvc.perform(get(BASE_URL)
                            .param("storeId", storeId.toString())
                            .param("from", TO)
                            .param("to", FROM))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("to must be after from"));
        }

        @Test
        @DisplayName("should return 400 through GlobalExceptionHandler when a required parameter is missing")
        void returns400WhenRequiredParameterMissing() throws Exception {
            mockMvc.perform(get(BASE_URL).param("storeId", storeId.toString()).param("from", FROM))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Missing required parameter 'to'"));
        }
    }
}
