package com.lifecontrol.api.scheduling.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityResponse;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityWindowRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityWindowResponse;
import com.lifecontrol.api.scheduling.service.SchedulingAvailabilityService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the {@code @PreAuthorize} role sets of the availability endpoints: the read adds
 * {@code lc-scheduling-read}, the write does not, and both accept {@code lc-scheduling}.
 */
@WebMvcTest(SchedulingAvailabilityController.class)
@Import({GlobalExceptionHandler.class, SchedulingAvailabilityControllerSecurityTest.TestSecurityConfig.class})
@DisplayName("SchedulingAvailabilityController Security — @PreAuthorize method-level authorization")
class SchedulingAvailabilityControllerSecurityTest {

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(basic -> {})
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .build();
        }
    }

    private static final String BASE_URL = "/api/scheduling/activities";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SchedulingAvailabilityService schedulingAvailabilityService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private UUID activityId;
    private SchedulingAvailabilityRequest request;

    @BeforeEach
    void setUp() {
        activityId = UUID.randomUUID();
        request = new SchedulingAvailabilityRequest(List.of(new SchedulingAvailabilityWindowRequest(
                1, LocalTime.of(9, 0), LocalTime.of(13, 0), LocalDate.of(2026, 9, 28), LocalDate.of(2026, 12, 31))));
        var response = new SchedulingAvailabilityResponse(
                activityId,
                List.of(new SchedulingAvailabilityWindowResponse(
                        UUID.randomUUID(),
                        1,
                        LocalTime.of(9, 0),
                        LocalTime.of(13, 0),
                        LocalDate.of(2026, 9, 28),
                        LocalDate.of(2026, 12, 31))));

        when(schedulingAvailabilityService.getAvailability(any())).thenReturn(response);
        when(schedulingAvailabilityService.replaceAvailability(any(), any(SchedulingAvailabilityRequest.class)))
                .thenReturn(response);
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{activityId}/availability")
    class GetAvailability {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{activityId}/availability", activityId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{activityId}/availability", activityId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-scheduling-read"})
        @DisplayName("returns 200 for the read-only scheduling role")
        void readOnlyRoleCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{activityId}/availability", activityId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-scheduling"})
        @DisplayName("returns 200 for the scheduling write role")
        void writeRoleCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{activityId}/availability", activityId))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 for lc-admin")
        void adminCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{activityId}/availability", activityId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{activityId}/availability")
    class ReplaceAvailability {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(put(BASE_URL + "/{activityId}/availability", activityId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(put(BASE_URL + "/{activityId}/availability", activityId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-scheduling-read"})
        @DisplayName("returns 403 for the read-only scheduling role")
        void readOnlyRoleCannotWrite() throws Exception {
            mockMvc.perform(put(BASE_URL + "/{activityId}/availability", activityId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-scheduling"})
        @DisplayName("returns 200 for the scheduling write role")
        void writeRoleCanWrite() throws Exception {
            mockMvc.perform(put(BASE_URL + "/{activityId}/availability", activityId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 for lc-admin")
        void adminCanWrite() throws Exception {
            mockMvc.perform(put(BASE_URL + "/{activityId}/availability", activityId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }
    }
}
