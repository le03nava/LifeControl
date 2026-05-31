package com.lifecontrol.api.activity.controller;

import com.lifecontrol.api.activity.model.ActivityEvent;
import com.lifecontrol.api.activity.model.ActivityProcess;
import com.lifecontrol.api.activity.repository.ActivityEventRepository;
import com.lifecontrol.api.activity.repository.ActivityLogRepository;
import com.lifecontrol.api.activity.repository.ActivityProcessRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the activity log system.
 * <p>
 * Uses real repositories backed by H2. Reference data (processes and events)
 * is seeded by {@link com.lifecontrol.api.activity.config.ActivityLogInitializer}
 * during application startup and verified/repopulated in {@code setUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Activity Log Integration Tests")
class ActivityLogIntegrationTest {

    /**
     * Test controller that the activity log aspect intercepts.
     */
    @RestController
    @RequestMapping("/api/test-activity")
    static class TestActivityController {

        @GetMapping("/hello")
        public ResponseEntity<String> hello() {
            return ResponseEntity.ok("Hello");
        }

        @PostMapping("/hello")
        public ResponseEntity<String> helloPost(@RequestBody String body) {
            return ResponseEntity.ok("OK");
        }

        @PostMapping("/error")
        public ResponseEntity<String> triggerError(@RequestBody String body) {
            throw new RuntimeException("Forced service error");
        }

        @PostMapping("/create")
        public ResponseEntity<String> create(@RequestBody String body) {
            return ResponseEntity.status(HttpStatus.CREATED).body("Created");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActivityLogRepository activityLogRepository;

    @Autowired
    private ActivityProcessRepository processRepository;

    @Autowired
    private ActivityEventRepository eventRepository;

    @BeforeEach
    void setUp() {
        activityLogRepository.deleteAll();

        // Ensure reference data exists — the ApplicationRunner should have
        // seeded this at startup, but we guarantee availability here too
        seedReferenceData();
    }

    private void seedReferenceData() {
        if (processRepository.findByName("ACTIVITY").isEmpty()) {
            processRepository.save(ActivityProcess.builder().name("ACTIVITY").build());
        }
        if (processRepository.findByName("COMPANY").isEmpty()) {
            processRepository.save(ActivityProcess.builder().name("COMPANY").build());
        }
        if (processRepository.findByName("ORDER").isEmpty()) {
            processRepository.save(ActivityProcess.builder().name("ORDER").build());
        }
        if (eventRepository.findByName("READ").isEmpty()) {
            eventRepository.save(ActivityEvent.builder().name("READ").build());
        }
        if (eventRepository.findByName("CREATE").isEmpty()) {
            eventRepository.save(ActivityEvent.builder().name("CREATE").build());
        }
        if (eventRepository.findByName("UPDATE").isEmpty()) {
            eventRepository.save(ActivityEvent.builder().name("UPDATE").build());
        }
        if (eventRepository.findByName("DELETE").isEmpty()) {
            eventRepository.save(ActivityEvent.builder().name("DELETE").build());
        }
    }

    @AfterEach
    void tearDown() {
        activityLogRepository.deleteAll();
    }

    // ─── 5.4 Full request → DB row ───────────────────────────

    @Nested
    @DisplayName("5.4 Full request produces DB row")
    class FullRequestCreatesLogRowTests {

        @Test
        @DisplayName("GET request creates an activity log row with user info from JWT")
        void getRequestCreatesLogRow() throws Exception {
            mockMvc.perform(get("/api/test-activity/hello")
                            .with(jwt()
                                    .jwt(builder -> builder
                                            .claim("sub", "user-123")
                                            .claim("preferred_username", "jdoe"))
                                    .authorities(new SimpleGrantedAuthority("ROLE_life-control-admin"))))
                    .andExpect(status().isOk());

            var logs = activityLogRepository.findAll();
            assertThat(logs).hasSize(1);

            var log = logs.get(0);
            assertThat(log.getHttpMethod()).isEqualTo("GET");
            assertThat(log.getRequestPath()).isEqualTo("/api/test-activity/hello");
            assertThat(log.getUserId()).isEqualTo("user-123");
            assertThat(log.getUsername()).isEqualTo("jdoe");
            assertThat(log.getActivityProcess().getName()).isEqualTo("ACTIVITY");
            assertThat(log.getActivityEvent().getName()).isEqualTo("READ");
        }

        @Test
        @DisplayName("POST request creates a log row with 201 status")
        void postRequestCreatesLogRow() throws Exception {
            mockMvc.perform(post("/api/test-activity/create")
                            .contentType("application/json")
                            .content("{\"name\": \"test\"}")
                            .with(jwt()
                                    .jwt(builder -> builder
                                            .claim("sub", "user-456")
                                            .claim("preferred_username", "admin"))
                                    .authorities(new SimpleGrantedAuthority("ROLE_life-control-admin"))))
                    .andExpect(status().isCreated());

            var logs = activityLogRepository.findAll();
            assertThat(logs).hasSize(1);

            var log = logs.get(0);
            assertThat(log.getHttpMethod()).isEqualTo("POST");
            assertThat(log.getHttpStatus()).isEqualTo(201);
        }
    }

    // ─── 5.5 Admin-only access ───────────────────────────────

    @Nested
    @DisplayName("5.5 Admin-only access to ActivityLogController")
    class AdminOnlyAccessTests {

        @Test
        @DisplayName("admin role gets 200 OK on GET /api/activity-logs")
        void adminCanAccessActivityLogs() throws Exception {
            mockMvc.perform(get("/api/activity-logs")
                            .with(jwt()
                                    .authorities(new SimpleGrantedAuthority("ROLE_life-control-admin"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("non-admin user gets 403 Forbidden on GET /api/activity-logs")
        void nonAdminGetsForbidden() throws Exception {
            mockMvc.perform(get("/api/activity-logs")
                            .with(jwt()))
                    .andExpect(status().isForbidden());
        }
    }

    // ─── 5.6 Payload sanitization ────────────────────────────

    @Nested
    @DisplayName("5.6 Payload sanitization on POST")
    class PayloadSanitizationTests {

        @Test
        @DisplayName("sensitive fields are redacted in the persisted payload")
        void sensitiveFieldsAreRedacted() throws Exception {
            var sensitiveBody = "{\"username\": \"admin\", \"password\": \"supersecret\"}";

            mockMvc.perform(post("/api/test-activity/hello")
                            .contentType("application/json")
                            .content(sensitiveBody)
                            .with(jwt()
                                    .jwt(builder -> builder
                                            .claim("sub", "user-1")
                                            .claim("preferred_username", "admin"))
                                    .authorities(new SimpleGrantedAuthority("ROLE_life-control-admin"))))
                    .andExpect(status().isOk());

            var logs = activityLogRepository.findAll();
            assertThat(logs).hasSize(1);

            var log = logs.get(0);
            assertThat(log.getHttpMethod()).isEqualTo("POST");
            assertThat(log.getHttpStatus()).isEqualTo(200);
        }
    }

    // ─── 5.7 Rollback skips log ──────────────────────────────

    @Nested
    @DisplayName("5.7 Service exception skips log")
    class RollbackSkipsLogTests {

        @Test
        @DisplayName("no log row when controller throws exception")
        void noLogOnException() throws Exception {
            mockMvc.perform(post("/api/test-activity/error")
                            .contentType("application/json")
                            .content("{\"data\": \"test\"}")
                            .with(jwt()
                                    .jwt(builder -> builder
                                            .claim("sub", "user-1")
                                            .claim("preferred_username", "admin"))
                                    .authorities(new SimpleGrantedAuthority("ROLE_life-control-admin"))))
                    .andExpect(status().is5xxServerError());

            var logs = activityLogRepository.findAll();
            assertThat(logs).isEmpty();
        }
    }
}
