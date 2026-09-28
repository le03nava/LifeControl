package com.lifecontrol.api.scheduling.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.scheduling.dto.SchedulingActivityRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingActivityResponse;
import com.lifecontrol.api.scheduling.service.SchedulingActivityService;
import java.time.LocalDateTime;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
 * Pins the {@code @PreAuthorize} role sets of the scheduling activity endpoints: the reads add
 * {@code lc-scheduling-read}, the writes do not, and both accept {@code lc-scheduling}.
 */
@WebMvcTest(SchedulingActivityController.class)
@Import({GlobalExceptionHandler.class, SchedulingActivityControllerSecurityTest.TestSecurityConfig.class})
@DisplayName("SchedulingActivityController Security — @PreAuthorize method-level authorization")
class SchedulingActivityControllerSecurityTest {

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
    private SchedulingActivityService schedulingActivityService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private UUID storeId;
    private SchedulingActivityRequest request;
    private SchedulingActivityResponse response;

    @BeforeEach
    void setUp() {
        storeId = UUID.randomUUID();
        request = new SchedulingActivityRequest(
                storeId, "employee-1", "Yoga", "A 60 minute yoga class", 60, 8, true, null);
        response = new SchedulingActivityResponse(
                UUID.randomUUID(),
                storeId,
                "employee-1",
                "Yoga",
                "A 60 minute yoga class",
                60,
                8,
                true,
                0L,
                LocalDateTime.now(),
                LocalDateTime.now());

        when(schedulingActivityService.getActivities(
                        any(), any(Boolean.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 12), 1));
        when(schedulingActivityService.getActivity(any())).thenReturn(response);
        when(schedulingActivityService.create(any(SchedulingActivityRequest.class)))
                .thenReturn(response);
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAllActivities {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL).param("storeId", storeId.toString())).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL).param("storeId", storeId.toString())).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-scheduling-read"})
        @DisplayName("returns 200 for the read-only scheduling role")
        void readOnlyRoleCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL).param("storeId", storeId.toString())).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-scheduling"})
        @DisplayName("returns 200 for the scheduling write role")
        void writeRoleCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL).param("storeId", storeId.toString())).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 200 for lc-admin")
        void adminCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL).param("storeId", storeId.toString())).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateActivity {

        @Test
        @WithMockUser
        @DisplayName("returns 403 for user with no roles")
        void noRolesGetsForbidden() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("returns 403 for an unrelated role")
        void unrelatedRoleGetsForbidden() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-scheduling-read"})
        @DisplayName("returns 403 for the read-only scheduling role")
        void readOnlyRoleCannotWrite() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"lc-scheduling"})
        @DisplayName("returns 201 for the scheduling write role")
        void writeRoleCanWrite() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("returns 201 for lc-admin")
        void adminCanWrite() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }
    }
}
