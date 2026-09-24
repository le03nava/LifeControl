package com.lifecontrol.api.exception;

import com.lifecontrol.api.salesorder.exception.InvalidSalesOrderChargeException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConflictException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConnectionException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates exceptions into the standard error envelope
 * ({@code status}/{@code message}/{@code path}/{@code timestamp}/{@code correlationId}).
 *
 * <p>Handlers are declared by category and resolve domain exceptions through
 * inheritance — {@link ResourceNotFoundException} covers every not-found subtype,
 * {@link ConflictException} covers duplicates and invalid state transitions.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ─── Not found (404) ────────────────────────────────────────────────

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    // ─── Duplicate / Conflict (409) ─────────────────────────────────────

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex) {
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * Handles database constraint violations that were not caught by an explicit
     * uniqueness check (e.g. a race between {@code existsBy…} and {@code save}).
     * Returns 409 instead of leaking a 500, without exposing SQL details.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        logger.warn("Data integrity violation", ex);
        return buildErrorResponse(
                HttpStatus.CONFLICT, "The operation conflicts with an existing resource or violates a data constraint");
    }

    /**
     * Handles an optimistic-locking conflict raised by a concurrent flush on an entity with a
     * {@code @Version} column. The exception carries no safe per-endpoint detail, so the response is
     * a generic 409 that leaks neither the entity, the SQL nor any version numbers.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException ex) {
        logger.warn("Optimistic locking failure", ex);
        return buildErrorResponse(
                HttpStatus.CONFLICT, "The operation conflicts with a concurrent modification; reload and retry");
    }

    // ─── Bad request (400) ──────────────────────────────────────────────

    @ExceptionHandler({IllegalArgumentException.class, InvalidSalesOrderChargeException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Handles invalid query/path parameters that cannot be converted to the expected
     * type (e.g. a malformed UUID in a path variable).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        var message = "Invalid value for parameter '" + ex.getName() + "'";
        return buildErrorResponse(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Handles malformed or unreadable request bodies (invalid JSON, wrong types).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        logger.debug("Unreadable request body: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body");
    }

    /**
     * Handles a missing required query parameter.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Missing required parameter '" + ex.getParameterName() + "'");
    }

    /**
     * Handles bean-validation failures on method parameters (path/query params)
     * instead of request bodies. Returns the same per-field error map as body validation.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ValidationErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getConstraintViolations()
                .forEach(violation -> errors.put(violation.getPropertyPath().toString(), violation.getMessage()));

        ValidationErrorResponse errorResponse = new ValidationErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                errors,
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    // ─── Identity provider ──────────────────────────────────────────────

    @ExceptionHandler(IdentityProviderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleIdentityProviderNotFound(IdentityProviderNotFoundException ex) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IdentityProviderConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdentityProviderConflict(IdentityProviderConflictException ex) {
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IdentityProviderConnectionException.class)
    public ResponseEntity<ErrorResponse> handleIdentityProviderConnection(IdentityProviderConnectionException ex) {
        logger.error("Identity provider connection failure", ex);
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Identity provider temporarily unavailable");
    }

    // ─── Validation (400) ───────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();

        ex.getBindingResult()
                .getFieldErrors()
                .forEach(fieldError -> errors.put(fieldError.getField(), fieldError.getDefaultMessage()));

        ValidationErrorResponse errorResponse = new ValidationErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                errors,
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    // ─── Access denied (403) ────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Access denied");
    }

    // ─── Method not allowed (405) ───────────────────────────────────────

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return buildErrorResponse(
                HttpStatus.METHOD_NOT_ALLOWED, "HTTP method " + ex.getMethod() + " is not supported for this endpoint");
    }

    // ─── Fallback (500) ─────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        logger.error("Unhandled exception", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatus status, String message) {
        ErrorResponse error =
                new ErrorResponse(status.value(), message, getCurrentPath(), LocalDateTime.now(), getCorrelationId());
        return ResponseEntity.status(status).body(error);
    }

    /**
     * Extracts the request path from the current web request context.
     * Returns {@code null} if no request context is available (e.g., in unit tests).
     */
    private String getCurrentPath() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest().getRequestURI();
        }
        return null;
    }

    /**
     * Extracts the correlation (trace) ID from the MDC context.
     * Uses Brave tracing bridge which populates {@code traceId} via Micrometer Tracing.
     * Returns {@code null} if no tracing context is available.
     */
    private String getCorrelationId() {
        return MDC.get("traceId");
    }

    public record ErrorResponse(
            int status, String message, String path, LocalDateTime timestamp, String correlationId) {}

    public record ValidationErrorResponse(
            int status,
            String message,
            Map<String, String> errors,
            String path,
            LocalDateTime timestamp,
            String correlationId) {}
}
