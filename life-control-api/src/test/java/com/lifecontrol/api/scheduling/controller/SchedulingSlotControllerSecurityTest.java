package com.lifecontrol.api.scheduling.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.scheduling.dto.SchedulingSlotResponse;
import com.lifecontrol.api.scheduling.service.SchedulingSlotService;
import java.time.LocalDateTime;
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
 * Pins the {@code @PreAuthorize} role set of the slot read endpoint: it materializes rows, so it
 * admits the read and the write scheduling roles and {@code lc-admin}, and rejects an anonymous
 * caller.
 */
@WebMvcTest(SchedulingSlotController.class)
@Import({GlobalExceptionHandler.class, SchedulingSlotControllerSecurityTest.TestSecurityConfig.class})
@DisplayName("SchedulingSlotController Security — @PreAuthorize method-level authorization")
class SchedulingSlotControllerSecurityTest {

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

    private static final String BASE_URL = "/api/scheduling/slots";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SchedulingSlotService schedulingSlotService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private UUID activityId;

    @BeforeEach
    void setUp() {
        activityId = UUID.randomUUID();
        when(schedulingSlotService.getSlots(any(), any(), any()))
                .thenReturn(List.of(new SchedulingSlotResponse(
                        UUID.randomUUID(),
                        activityId,
                        LocalDateTime.of(2026, 9, 28, 9, 0),
                        LocalDateTime.of(2026, 9, 28, 10, 0),
                        4,
                        0,
                        4,
                        "Available",
                        true)));
    }

    @Test
    @WithMockUser(roles = {"lc-scheduling-read"})
    @DisplayName("returns 200 for the read-only scheduling role")
    void readOnlyRoleCanRead() throws Exception {
        mockMvc.perform(slotRequest()).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"lc-scheduling"})
    @DisplayName("returns 200 for the scheduling write role")
    void writeRoleCanRead() throws Exception {
        mockMvc.perform(slotRequest()).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"lc-admin"})
    @DisplayName("returns 200 for lc-admin")
    void adminCanRead() throws Exception {
        mockMvc.perform(slotRequest()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("rejects an unauthenticated request")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(slotRequest()).andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder slotRequest() {
        return get(BASE_URL)
                .param("activityId", UUID.randomUUID().toString())
                .param("from", "2026-09-28T00:00:00")
                .param("to", "2026-09-29T00:00:00");
    }
}
