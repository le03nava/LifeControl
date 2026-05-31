package com.lifecontrol.api.activity.service;

import com.lifecontrol.api.activity.dto.ActivityLogFilter;
import com.lifecontrol.api.activity.dto.ActivityLogResponse;
import com.lifecontrol.api.activity.event.ActivityLogEvent;
import com.lifecontrol.api.activity.model.ActivityEvent;
import com.lifecontrol.api.activity.model.ActivityLog;
import com.lifecontrol.api.activity.model.ActivityProcess;
import com.lifecontrol.api.activity.repository.ActivityEventRepository;
import com.lifecontrol.api.activity.repository.ActivityLogRepository;
import com.lifecontrol.api.activity.repository.ActivityProcessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityLogService Tests")
class ActivityLogServiceTest {

    @Mock
    private ActivityLogRepository activityLogRepository;

    @Mock
    private ActivityProcessRepository processRepository;

    @Mock
    private ActivityEventRepository eventRepository;

    private ActivityLogService service;

    @Captor
    private ArgumentCaptor<ActivityLog> logCaptor;

    private ActivityProcess testProcess;
    private ActivityEvent testEvent;
    private ActivityLogEvent testLogEvent;

    @BeforeEach
    void setUp() {
        service = new ActivityLogService(activityLogRepository, processRepository, eventRepository);

        testProcess = ActivityProcess.builder().name("COMPANY").build();
        testEvent = ActivityEvent.builder().name("READ").build();

        testLogEvent = new ActivityLogEvent(
                this, "user-1", "testuser",
                "COMPANY", "READ",
                "GET", 200,
                "/api/companies", "127.0.0.1",
                "TestAgent", "{\"name\": \"test\"}"
        );
    }

    @Nested
    @DisplayName("save()")
    class SaveTests {

        @Test
        @DisplayName("should persist a valid log entry")
        void persistsLogEntry() {
            when(processRepository.findByName("COMPANY")).thenReturn(Optional.of(testProcess));
            when(eventRepository.findByName("READ")).thenReturn(Optional.of(testEvent));

            service.save(testLogEvent);

            verify(activityLogRepository).save(logCaptor.capture());
            var saved = logCaptor.getValue();
            assertThat(saved.getUserId()).isEqualTo("user-1");
            assertThat(saved.getUsername()).isEqualTo("testuser");
            assertThat(saved.getActivityProcess().getName()).isEqualTo("COMPANY");
            assertThat(saved.getActivityEvent().getName()).isEqualTo("READ");
            assertThat(saved.getHttpMethod()).isEqualTo("GET");
            assertThat(saved.getHttpStatus()).isEqualTo(200);
            assertThat(saved.getRequestPath()).isEqualTo("/api/companies");
            assertThat(saved.getIpAddress()).isEqualTo("127.0.0.1");
            assertThat(saved.getUserAgent()).isEqualTo("TestAgent");
            assertThat(saved.getPayloadJson()).isEqualTo("{\"name\": \"test\"}");
        }

        @Test
        @DisplayName("should sanitize sensitive fields in payload")
        void sanitizesSensitiveFields() {
            when(processRepository.findByName("COMPANY")).thenReturn(Optional.of(testProcess));
            when(eventRepository.findByName("READ")).thenReturn(Optional.of(testEvent));

            var sensitiveEvent = new ActivityLogEvent(
                    this, "user-1", "testuser",
                    "COMPANY", "READ",
                    "POST", 201,
                    "/api/login", "127.0.0.1",
                    "TestAgent",
                    "{\"username\": \"admin\", \"password\": \"secret123\"}"
            );

            service.save(sensitiveEvent);

            verify(activityLogRepository).save(logCaptor.capture());
            var saved = logCaptor.getValue();
            assertThat(saved.getPayloadJson())
                    .contains("\"password\": \"[REDACTED]\"")
                    .contains("\"username\": \"admin\"");
        }

        @Test
        @DisplayName("should skip when process is not found")
        void skipsWhenProcessNotFound() {
            when(processRepository.findByName("COMPANY")).thenReturn(Optional.empty());

            service.save(testLogEvent);

            verify(activityLogRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("findAll()")
    class FindAllTests {

        @Test
        @DisplayName("should return paginated results")
        void returnsPaginatedResults() {
            var now = LocalDateTime.now();
            var logEntry = ActivityLog.builder()
                    .userId("user-1")
                    .username("testuser")
                    .activityProcess(testProcess)
                    .activityEvent(testEvent)
                    .httpMethod("GET")
                    .httpStatus(200)
                    .requestPath("/api/companies")
                    .ipAddress("127.0.0.1")
                    .userAgent("TestAgent")
                    .build();

            var pageable = PageRequest.of(0, 20);
            var page = new PageImpl<>(List.of(logEntry), pageable, 1);
            when(activityLogRepository.findByFilters(
                    any(), any(), any(), any(), any(), any(), eq(pageable)))
                    .thenReturn(page);

            var filter = new ActivityLogFilter(
                    LocalDate.of(2025, 1, 1),
                    LocalDate.of(2025, 12, 31),
                    "COMPANY", "READ", "user-1", "GET"
            );

            Page<ActivityLogResponse> result = service.findAll(filter, pageable);

            assertThat(result).hasSize(1);
            var response = result.getContent().get(0);
            assertThat(response.process()).isEqualTo("COMPANY");
            assertThat(response.event()).isEqualTo("READ");
            assertThat(response.httpMethod()).isEqualTo("GET");
        }
    }
}
