package com.lifecontrol.api.activity.aspect;

import com.lifecontrol.api.activity.annotation.ActivityLog;
import com.lifecontrol.api.activity.event.ActivityLogEvent;
import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.controller.CompanyController;
import com.lifecontrol.api.country.controller.CountryController;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityLogAspect Tests")
class ActivityLogAspectTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    private ActivityLogAspect aspect;

    @Captor
    private ArgumentCaptor<ActivityLogEvent> eventCaptor;

    @BeforeEach
    void setUp() {
        aspect = new ActivityLogAspect(eventPublisher, currentUserContext);
    }

    private void mockRequest(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");
        var attrs = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attrs);
    }

    // ── Process resolution ───────────────────────────────────

    @Nested
    @DisplayName("Process resolution from package name")
    class ProcessResolutionTests {

        @BeforeEach
        void setUp() throws Throwable {
            mockRequest("GET", "/api/companies");
            given(joinPoint.getSignature()).willReturn(methodSignature);
            willReturn(ResponseEntity.ok("OK")).given(joinPoint).proceed();
            given(currentUserContext.getUserId()).willReturn("user-1");
            given(currentUserContext.getUsername()).willReturn("testuser");
        }

        @Test
        @DisplayName("should resolve COMPANY from company.controller package")
        void companyController() throws Throwable {
            given(methodSignature.getDeclaringType()).willReturn(CompanyController.class);
            given(methodSignature.getMethod()).willReturn(
                    CompanyController.class.getMethod("getAllCompanies", Pageable.class, String.class));

            aspect.logActivity(joinPoint);

            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getProcessName()).isEqualTo("COMPANY");
            assertThat(eventCaptor.getValue().getEventName()).isEqualTo("READ");
        }

        @Test
        @DisplayName("should resolve COUNTRY from country.controller package")
        void countryController() throws Throwable {
            given(methodSignature.getDeclaringType()).willReturn(CountryController.class);
            given(methodSignature.getMethod()).willReturn(
                    CountryController.class.getMethod("getAllCountries", boolean.class));

            aspect.logActivity(joinPoint);

            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getProcessName()).isEqualTo("COUNTRY");
        }
    }

    // ── Event resolution from HTTP method ────────────────────

    @Nested
    @DisplayName("Event resolution from HTTP method")
    class EventResolutionTests {

        @BeforeEach
        void setUp() throws Throwable {
            given(joinPoint.getSignature()).willReturn(methodSignature);
            given(methodSignature.getDeclaringType()).willReturn(CompanyController.class);
            given(methodSignature.getMethod()).willReturn(
                    CompanyController.class.getMethod("getAllCompanies", Pageable.class, String.class));
            willReturn(ResponseEntity.ok("OK")).given(joinPoint).proceed();
            given(currentUserContext.getUserId()).willReturn("user-1");
            given(currentUserContext.getUsername()).willReturn("testuser");
        }

        @Test
        @DisplayName("GET should resolve to READ")
        void getMapsToRead() throws Throwable {
            mockRequest("GET", "/api/companies");
            aspect.logActivity(joinPoint);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getEventName()).isEqualTo("READ");
        }

        @Test
        @DisplayName("POST should resolve to CREATE")
        void postMapsToCreate() throws Throwable {
            mockRequest("POST", "/api/companies");
            aspect.logActivity(joinPoint);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getEventName()).isEqualTo("CREATE");
        }

        @Test
        @DisplayName("PUT should resolve to UPDATE")
        void putMapsToUpdate() throws Throwable {
            mockRequest("PUT", "/api/companies/123");
            aspect.logActivity(joinPoint);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getEventName()).isEqualTo("UPDATE");
        }

        @Test
        @DisplayName("DELETE should resolve to DELETE")
        void deleteMapsToDelete() throws Throwable {
            mockRequest("DELETE", "/api/companies/123");
            aspect.logActivity(joinPoint);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getEventName()).isEqualTo("DELETE");
        }
    }

    // ── @ActivityLog annotation override ─────────────────────

    @Nested
    @DisplayName("@ActivityLog annotation override")
    class ActivityLogAnnotationTests {

        @BeforeEach
        void setUp() throws Throwable {
            mockRequest("GET", "/api/custom");
            given(joinPoint.getSignature()).willReturn(methodSignature);
            given(methodSignature.getDeclaringType()).willReturn(CustomController.class);
            given(methodSignature.getMethod()).willReturn(
                    CustomController.class.getMethod("customEndpoint"));
            willReturn(ResponseEntity.ok("OK")).given(joinPoint).proceed();
            given(currentUserContext.getUserId()).willReturn("user-1");
            given(currentUserContext.getUsername()).willReturn("testuser");
        }

        @Test
        @DisplayName("should use annotation process/event override")
        void overridesProcessAndEvent() throws Throwable {
            aspect.logActivity(joinPoint);

            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getProcessName()).isEqualTo("CUSTOM_PROCESS");
            assertThat(eventCaptor.getValue().getEventName()).isEqualTo("CUSTOM_EVENT");
        }
    }

    // ── Skip paths ───────────────────────────────────────────

    @Nested
    @DisplayName("Skip paths")
    class SkipPathTests {

        @Test
        @DisplayName("should skip actuator endpoints")
        void skipsActuator() throws Throwable {
            mockRequest("GET", "/actuator/health");
            willReturn(ResponseEntity.ok("OK")).given(joinPoint).proceed();

            aspect.logActivity(joinPoint);

            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("should skip swagger endpoints")
        void skipsSwagger() throws Throwable {
            mockRequest("GET", "/swagger-ui/index.html");
            willReturn(ResponseEntity.ok("OK")).given(joinPoint).proceed();

            aspect.logActivity(joinPoint);

            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    // ── HTTP Status resolution ───────────────────────────────

    @Nested
    @DisplayName("HTTP status resolution")
    class HttpStatusResolutionTests {

        @BeforeEach
        void setUp() throws Throwable {
            mockRequest("GET", "/api/companies");
            given(joinPoint.getSignature()).willReturn(methodSignature);
            given(methodSignature.getDeclaringType()).willReturn(CompanyController.class);
            given(methodSignature.getMethod()).willReturn(
                    CompanyController.class.getMethod("getAllCompanies", Pageable.class, String.class));
            given(currentUserContext.getUserId()).willReturn("user-1");
            given(currentUserContext.getUsername()).willReturn("testuser");
        }

        @Test
        @DisplayName("should capture 200 from successful ResponseEntity")
        void captures200() throws Throwable {
            willReturn(ResponseEntity.ok("OK")).given(joinPoint).proceed();

            aspect.logActivity(joinPoint);

            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getHttpStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("should capture 201 from created ResponseEntity")
        void captures201() throws Throwable {
            willReturn(ResponseEntity.status(201).body("Created")).given(joinPoint).proceed();

            aspect.logActivity(joinPoint);

            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getHttpStatus()).isEqualTo(201);
        }
    }

    // ── Custom controller for annotation override test ──

    static class CustomController {
        @ActivityLog(process = "CUSTOM_PROCESS", event = "CUSTOM_EVENT")
        public ResponseEntity<String> customEndpoint() {
            return ResponseEntity.ok("OK");
        }
    }
}
