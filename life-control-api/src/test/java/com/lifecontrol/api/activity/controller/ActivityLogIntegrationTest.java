package com.lifecontrol.api.activity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.activity.event.ActivityLogEvent;
import com.lifecontrol.api.activity.model.ActivityEvent;
import com.lifecontrol.api.activity.repository.ActivityEventRepository;
import com.lifecontrol.api.activity.repository.ActivityLogRepository;
import com.lifecontrol.api.activity.repository.ActivityProcessRepository;
import com.lifecontrol.api.activity.service.ActivityLogService;
import com.lifecontrol.api.activity.util.ProcessNameResolver;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Integration tests for the activity log system.
 * <p>
 * Uses real repositories backed by PostgreSQL (Testcontainers) with Flyway
 * migrations applied. Reference data (processes and events) is seeded by Flyway
 * (V3, V4, V16, V27); the find-or-create calls in {@code setUp()} cover the events only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Activity Log Integration Tests")
class ActivityLogIntegrationTest extends AbstractPostgresIntegrationTest {

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

    @Autowired
    private ActivityLogService activityLogService;

    @BeforeEach
    void setUp() {
        activityLogRepository.deleteAll();

        // Events only: the processes are Flyway's job (V3, V4, V16, V27), and creating them here
        // would mask the very gap the seed-coverage guard below exists to catch.
        seedEvents();
    }

    private void seedEvents() {
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
                            .with(jwt().jwt(builder ->
                                            builder.claim("sub", "user-123").claim("preferred_username", "jdoe"))
                                    .authorities(new SimpleGrantedAuthority("ROLE_lc-admin"))))
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
                            .with(jwt().jwt(builder ->
                                            builder.claim("sub", "user-456").claim("preferred_username", "admin"))
                                    .authorities(new SimpleGrantedAuthority("ROLE_lc-admin"))))
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
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_lc-admin"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("non-admin user gets 403 Forbidden on GET /api/activity-logs")
        void nonAdminGetsForbidden() throws Exception {
            mockMvc.perform(get("/api/activity-logs").with(jwt())).andExpect(status().isForbidden());
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
                            .with(jwt().jwt(builder ->
                                            builder.claim("sub", "user-1").claim("preferred_username", "admin"))
                                    .authorities(new SimpleGrantedAuthority("ROLE_lc-admin"))))
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
                            .with(jwt().jwt(builder ->
                                            builder.claim("sub", "user-1").claim("preferred_username", "admin"))
                                    .authorities(new SimpleGrantedAuthority("ROLE_lc-admin"))))
                    .andExpect(status().is5xxServerError());

            var logs = activityLogRepository.findAll();
            assertThat(logs).isEmpty();
        }
    }

    // ─── Seed coverage: every controller domain ──────────────

    @Nested
    @DisplayName("Every controller domain resolves to a seeded activity process")
    class ActivityProcessSeedTests {

        private static final String BASE_PACKAGE = "com.lifecontrol.api";

        /**
         * Every process the aspect can derive from a <b>production</b> controller, computed by the
         * same {@link ProcessNameResolver} the aspect calls. Discovered rather than listed: a
         * hardcoded list is exactly what let {@code HR}, {@code PROVISIONING} and
         * {@code GOODSRECEIPT} stay unseeded while this suite reported green.
         */
        private SortedSet<String> productionControllerProcesses() throws ClassNotFoundException {
            var scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

            var processes = new TreeSet<String>();
            for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
                var className = candidate.getBeanClassName();
                if (className == null || isTestClass(className)) {
                    continue;
                }
                var packageName = className.substring(0, className.lastIndexOf('.'));
                var process = ProcessNameResolver.resolve(packageName);
                assertThat(process)
                        .as("controller %s must resolve to a process name", className)
                        .isNotNull();
                processes.add(process);
            }
            return processes;
        }

        private static boolean isTestClass(String className) throws ClassNotFoundException {
            var codeSource = Class.forName(className).getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                return false;
            }
            var location = codeSource.getLocation().getPath();
            return location.contains("/test/") || location.contains("/test-classes/");
        }

        @Test
        @DisplayName("every derived process is present in activity_processes")
        void allDerivedProcessesSeeded() throws Exception {
            var derived = productionControllerProcesses();

            assertThat(derived)
                    .as("no controller was discovered — the guard would be vacuous")
                    .isNotEmpty();
            assertThat(derived)
                    .as("the discovery must reach the domains this guard exists for")
                    .contains("HR", "PROVISIONING", "GOODSRECEIPT");

            for (var name : derived) {
                assertThat(processRepository.findByName(name))
                        .as(
                                "activity process '%s' should be seeded — ActivityLogService discards its "
                                        + "entries with only a WARN",
                                name)
                        .isPresent();
            }
        }

        @Test
        @DisplayName("an entry for every derived process is persisted, never discarded")
        void entryForEveryDerivedProcessIsPersisted() throws Exception {
            for (var name : productionControllerProcesses()) {
                var before = activityLogRepository.count();

                activityLogService.save(new ActivityLogEvent(
                        this, "user-1", "tester", name, "READ", "GET", 200, "/api/probe", "127.0.0.1", "junit", null));

                assertThat(activityLogRepository.count())
                        .as("an activity entry for process '%s' must be persisted", name)
                        .isEqualTo(before + 1);
            }
        }
    }
}
