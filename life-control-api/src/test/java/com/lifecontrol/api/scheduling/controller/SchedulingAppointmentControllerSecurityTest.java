package com.lifecontrol.api.scheduling.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.scheduling.dto.SchedulingAppointmentResponse;
import com.lifecontrol.api.scheduling.service.SchedulingAppointmentService;
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
 * Pins the {@code @PreAuthorize} role sets of the appointment endpoints: the read adds
 * {@code lc-scheduling-read}, the writes do not, and both accept {@code lc-scheduling} and
 * {@code lc-admin}.
 */
@WebMvcTest(SchedulingAppointmentController.class)
@Import({GlobalExceptionHandler.class, SchedulingAppointmentControllerSecurityTest.TestSecurityConfig.class})
@DisplayName("SchedulingAppointmentController Security — @PreAuthorize method-level authorization")
class SchedulingAppointmentControllerSecurityTest {

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

    private static final String BASE_URL = "/api/scheduling/appointments";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SchedulingAppointmentService schedulingAppointmentService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private UUID appointmentId;
    private UUID slotId;
    private String body;

    @BeforeEach
    void setUp() throws Exception {
        appointmentId = UUID.randomUUID();
        slotId = UUID.randomUUID();
        body = "{\"slotId\":\"" + slotId + "\"}";

        when(schedulingAppointmentService.create(any()))
                .thenReturn(new SchedulingAppointmentResponse(
                        appointmentId,
                        slotId,
                        LocalDateTime.of(2026, 9, 28, 9, 0),
                        LocalDateTime.of(2026, 9, 28, 10, 0),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        UUID.randomUUID(),
                        "Scheduled",
                        null,
                        true,
                        0L,
                        LocalDateTime.now(),
                        LocalDateTime.now()));
        when(schedulingAppointmentService.getById(any()))
                .thenReturn(new SchedulingAppointmentResponse(
                        appointmentId,
                        slotId,
                        LocalDateTime.of(2026, 9, 28, 9, 0),
                        LocalDateTime.of(2026, 9, 28, 10, 0),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        UUID.randomUUID(),
                        "Scheduled",
                        null,
                        true,
                        0L,
                        LocalDateTime.now(),
                        LocalDateTime.now()));
        when(schedulingAppointmentService.reschedule(eq(appointmentId), any()))
                .thenReturn(new SchedulingAppointmentResponse(
                        appointmentId,
                        slotId,
                        LocalDateTime.of(2026, 9, 28, 9, 0),
                        LocalDateTime.of(2026, 9, 28, 10, 0),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        UUID.randomUUID(),
                        "Scheduled",
                        null,
                        true,
                        0L,
                        LocalDateTime.now(),
                        LocalDateTime.now()));
        when(schedulingAppointmentService.updateStatus(eq(appointmentId), any()))
                .thenReturn(new SchedulingAppointmentResponse(
                        appointmentId,
                        slotId,
                        LocalDateTime.of(2026, 9, 28, 9, 0),
                        LocalDateTime.of(2026, 9, 28, 10, 0),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        null,
                        UUID.randomUUID(),
                        "Confirmed",
                        null,
                        true,
                        0L,
                        LocalDateTime.now(),
                        LocalDateTime.now()));
        when(schedulingAppointmentService.getAppointments(any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    // ─── GET (read) ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET " + BASE_URL + "/{id}")
    class ReadTests {

        @Test
        @WithMockUser(roles = {"lc-scheduling-read"})
        @DisplayName("admits the read-only role")
        void readOnlyRoleCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL + "/" + appointmentId)).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-scheduling"})
        @DisplayName("admits the write role")
        void writeRoleCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL + "/" + appointmentId)).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("admits lc-admin")
        void adminCanRead() throws Exception {
            mockMvc.perform(get(BASE_URL + "/" + appointmentId)).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("rejects an unrelated role")
        void unrelatedRoleIsForbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/" + appointmentId)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("rejects an unauthenticated request")
        void unauthenticatedIsRejected() throws Exception {
            mockMvc.perform(get(BASE_URL + "/" + appointmentId)).andExpect(status().isUnauthorized());
        }
    }

    // ─── Writes ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("write endpoints")
    class WriteTests {

        @Test
        @WithMockUser(roles = {"lc-scheduling"})
        @DisplayName("admit lc-scheduling on POST, PUT, PATCH and DELETE")
        void writeRoleAdmitted() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
            mockMvc.perform(put(BASE_URL + "/" + appointmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
            mockMvc.perform(patch(BASE_URL + "/" + appointmentId + "/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"statusId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(delete(BASE_URL + "/" + appointmentId)).andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-admin"})
        @DisplayName("admit lc-admin on the writes")
        void adminAdmitted() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/" + appointmentId)).andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(roles = {"lc-scheduling-read"})
        @DisplayName("deny the read-only role on every write")
        void readOnlyRoleIsDeniedOnWrites() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put(BASE_URL + "/" + appointmentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch(BASE_URL + "/" + appointmentId + "/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"statusId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete(BASE_URL + "/" + appointmentId)).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser
        @DisplayName("deny a caller with no roles")
        void noRolesIsForbidden() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/" + appointmentId)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("reject an unauthenticated write")
        void unauthenticatedWriteIsRejected() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/" + appointmentId)).andExpect(status().isUnauthorized());
        }
    }

    // ─── GET list (read) ────────────────────────────────────────────

    @Nested
    @DisplayName("GET " + BASE_URL)
    class ListEndpointTests {

        @Test
        @WithMockUser(roles = {"lc-scheduling-read"})
        @DisplayName("admits the read-only role")
        void readOnlyRoleCanList() throws Exception {
            mockMvc.perform(listRequest()).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"lc-scheduling"})
        @DisplayName("does not block a principal holding only the write role")
        void writeRoleCanList() throws Exception {
            mockMvc.perform(listRequest()).andExpect(status().isOk());
        }

        @Test
        @WithMockUser(roles = {"other-role"})
        @DisplayName("rejects a caller with no scheduling role")
        void unrelatedRoleIsForbidden() throws Exception {
            mockMvc.perform(listRequest()).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("rejects an unauthenticated list request")
        void unauthenticatedIsRejected() throws Exception {
            mockMvc.perform(listRequest()).andExpect(status().isUnauthorized());
        }

        private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder listRequest() {
            return get(BASE_URL)
                    .param("storeId", UUID.randomUUID().toString())
                    .param("from", "2026-09-28T00:00:00")
                    .param("to", "2026-09-29T00:00:00");
        }
    }
}
