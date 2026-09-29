package com.lifecontrol.api.scheduling.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.scheduling.service.SchedulingCalendarService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
 * Pins the {@code @PreAuthorize} role set of the calendar projection endpoint: it mutates nothing, so
 * it admits the write and the read-only scheduling roles and {@code lc-admin}, and rejects a caller
 * with no scheduling role and an anonymous one.
 */
@WebMvcTest(SchedulingCalendarController.class)
@Import({GlobalExceptionHandler.class, SchedulingCalendarControllerSecurityTest.TestSecurityConfig.class})
@DisplayName("SchedulingCalendarController Security — @PreAuthorize method-level authorization")
class SchedulingCalendarControllerSecurityTest {

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

    private static final String BASE_URL = "/api/scheduling/calendar";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SchedulingCalendarService schedulingCalendarService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    @BeforeEach
    void setUp() {
        when(schedulingCalendarService.getCalendar(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @WithMockUser(roles = {"lc-scheduling-read"})
    @DisplayName("returns 200 for the read-only scheduling role")
    void readOnlyRoleCanRead() throws Exception {
        mockMvc.perform(calendarRequest()).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"lc-scheduling"})
    @DisplayName("does not block a principal holding only the write role")
    void writeRoleCanRead() throws Exception {
        mockMvc.perform(calendarRequest()).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"lc-admin"})
    @DisplayName("returns 200 for lc-admin")
    void adminCanRead() throws Exception {
        mockMvc.perform(calendarRequest()).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"other-role"})
    @DisplayName("rejects a caller with no scheduling role")
    void unrelatedRoleIsForbidden() throws Exception {
        mockMvc.perform(calendarRequest()).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("rejects an unauthenticated request")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(calendarRequest()).andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder calendarRequest() {
        return get(BASE_URL)
                .param("storeId", UUID.randomUUID().toString())
                .param("from", "2026-09-28T00:00:00")
                .param("to", "2026-09-29T00:00:00");
    }
}
