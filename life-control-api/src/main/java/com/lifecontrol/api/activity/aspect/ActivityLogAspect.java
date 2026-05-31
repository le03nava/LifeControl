package com.lifecontrol.api.activity.aspect;

import com.lifecontrol.api.activity.annotation.ActivityLog;
import com.lifecontrol.api.activity.event.ActivityLogEvent;
import com.lifecontrol.api.common.auth.CurrentUserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Aspect that logs every REST controller invocation to the activity audit trail.
 * <p>
 * Uses {@code @Around("@within(org.springframework.web.bind.annotation.RestController)")}
 * to intercept all {@link org.springframework.web.bind.annotation.RestController}
 * beans. Resolves the process name from the controller's package (the domain
 * segment right after {@code com.lifecontrol.api.}), and the event name from
 * the HTTP method. Both can be overridden with {@link ActivityLog}.
 * <p>
 * Runs at {@link Ordered#HIGHEST_PRECEDENCE} so it fires before security
 * and other concerns — but only captures data after the controller method
 * returns successfully. Actuator and swagger endpoints are skipped.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActivityLogAspect {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogAspect.class);

    private static final List<String> SKIP_PATHS = List.of(
            "/actuator", "/swagger-ui", "/v3/api-docs", "/api-docs",
            "/swagger-resources", "/aggregate"
    );

    private static final String DEFAULT_EVENT_READ = "READ";
    private static final String DEFAULT_EVENT_CREATE = "CREATE";
    private static final String DEFAULT_EVENT_UPDATE = "UPDATE";
    private static final String DEFAULT_EVENT_DELETE = "DELETE";

    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUserContext currentUserContext;

    public ActivityLogAspect(ApplicationEventPublisher eventPublisher,
                             CurrentUserContext currentUserContext) {
        this.eventPublisher = eventPublisher;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Intercepts all {@code @RestController} beans, captures request metadata,
     * executes the controller method, then publishes an {@link ActivityLogEvent}.
     */
    @Around("@within(org.springframework.web.bind.annotation.RestController)")
    public Object logActivity(ProceedingJoinPoint joinPoint) throws Throwable {
        var requestAttributes = RequestContextHolder.getRequestAttributes();
        if (!(requestAttributes instanceof ServletRequestAttributes servletAttributes)) {
            return joinPoint.proceed();
        }

        var request = servletAttributes.getRequest();
        var path = request.getRequestURI();

        // Skip non-API endpoints early (actuator, swagger, etc.)
        if (shouldSkip(path)) {
            return joinPoint.proceed();
        }

        var httpMethod = request.getMethod();

        // Resolve process and event — annotation overrides auto-resolution
        var resolved = resolveProcessAndEvent(joinPoint, httpMethod);
        var processName = resolved.processName;
        var eventName = resolved.eventName;

        if (processName == null || eventName == null) {
            // Unable to resolve — proceed without logging
            return joinPoint.proceed();
        }

        // Execute the controller method
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Exception e) {
            // On exception, no activity log is written
            throw e;
        }

        // Capture response status
        var httpStatus = resolveHttpStatus(result);

        // Capture user info
        var userId = currentUserContext.getUserId();
        var username = currentUserContext.getUsername();

        // Capture request metadata
        var ipAddress = resolveIpAddress(request);
        var userAgent = request.getHeader("User-Agent");
        var payloadJson = getPayload(request);

        try {
            var event = new ActivityLogEvent(
                    this, userId, username, processName, eventName,
                    httpMethod, httpStatus, path, ipAddress, userAgent, payloadJson
            );
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            // Activity logging is best-effort — never fail the original request
            log.warn("Failed to publish activity log event: {}", e.getMessage());
        }

        return result;
    }

    // ── Private helpers ──────────────────────────────────────

    private boolean shouldSkip(String path) {
        return SKIP_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * Resolves the process and event names for this controller invocation.
     * <p>
     * Process is derived from the package name: the segment immediately after
     * {@code com.lifecontrol.api.} (e.g. {@code company} &rarr; {@code COMPANY}).
     * Event is derived from the HTTP method (GET &rarr; READ, POST &rarr; CREATE,
     * PUT/PATCH &rarr; UPDATE, DELETE &rarr; DELETE).
     * <p>
     * If the method carries an {@link ActivityLog} annotation, its non-empty
     * attributes override the auto-resolved values.
     */
    private ProcessEventResult resolveProcessAndEvent(ProceedingJoinPoint joinPoint,
                                                       String httpMethod) {
        var signature = joinPoint.getSignature();
        var declaringType = signature.getDeclaringType();
        var packageName = declaringType.getPackageName();

        var processName = resolveProcessFromPackage(packageName);
        var eventName = resolveEventFromHttpMethod(httpMethod);

        // Check for @ActivityLog override on the method
        if (signature instanceof MethodSignature methodSignature) {
            Method method = methodSignature.getMethod();
            var annotation = method.getAnnotation(ActivityLog.class);
            if (annotation != null) {
                if (!annotation.process().isBlank()) {
                    processName = annotation.process().toUpperCase();
                }
                if (!annotation.event().isBlank()) {
                    eventName = annotation.event().toUpperCase();
                }
            }
        }

        return new ProcessEventResult(processName, eventName);
    }

    /**
     * Extracts the domain name from the package structure.
     * Expects the format: {@code com.lifecontrol.api.<domain>.controller...}
     * Falls back to the segment after {@code api.} if the package doesn't
     * follow the standard pattern.
     */
    private static String resolveProcessFromPackage(String packageName) {
        var apiPrefix = "com.lifecontrol.api.";
        if (!packageName.startsWith(apiPrefix)) {
            return null;
        }
        var afterApi = packageName.substring(apiPrefix.length());
        var dotIndex = afterApi.indexOf('.');
        var domain = (dotIndex > 0) ? afterApi.substring(0, dotIndex) : afterApi;
        return domain.isEmpty() ? null : domain.toUpperCase();
    }

    /**
     * Maps HTTP methods to activity event names.
     */
    private static String resolveEventFromHttpMethod(String httpMethod) {
        return switch (httpMethod.toUpperCase()) {
            case "GET" -> DEFAULT_EVENT_READ;
            case "POST" -> DEFAULT_EVENT_CREATE;
            case "PUT", "PATCH" -> DEFAULT_EVENT_UPDATE;
            case "DELETE" -> DEFAULT_EVENT_DELETE;
            default -> null;
        };
    }

    /**
     * Extracts the HTTP status code from the controller's return value.
     * Handles {@link ResponseEntity} and plain objects (defaults to 200).
     */
    private static int resolveHttpStatus(Object result) {
        if (result instanceof ResponseEntity<?> entity) {
            return entity.getStatusCode().value();
        }
        return 200;
    }

    /**
     * Resolves the client IP from the {@code X-Forwarded-For} header or
     * falls back to {@link HttpServletRequest#getRemoteAddr()}.
     */
    private static String resolveIpAddress(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            var comma = forwarded.indexOf(',');
            return (comma > 0) ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Reads the cached request body from {@link ContentCachingRequestWrapper}.
     * Returns {@code null} if no body was cached (GET requests, empty bodies).
     */
    private static String getPayload(HttpServletRequest request) {
        if (!(request instanceof ContentCachingRequestWrapper wrapper)) {
            return null;
        }
        var buf = wrapper.getContentAsByteArray();
        if (buf == null || buf.length == 0) {
            return null;
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    /**
     * Simple holder for resolved process and event names.
     */
    private record ProcessEventResult(String processName, String eventName) {}
}
