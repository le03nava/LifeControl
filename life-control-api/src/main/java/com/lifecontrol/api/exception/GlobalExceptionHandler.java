package com.lifecontrol.api.exception;

import com.lifecontrol.api.salesorder.exception.InvalidSalesOrderChargeException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConflictException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderConnectionException;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

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

    // ─── Bad request (400) ──────────────────────────────────────────────

    @ExceptionHandler({IllegalArgumentException.class, InvalidSalesOrderChargeException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
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

        ex.getBindingResult().getFieldErrors().forEach(fieldError ->
                errors.put(fieldError.getField(), fieldError.getDefaultMessage())
        );

        ValidationErrorResponse errorResponse = new ValidationErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                errors,
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    // ─── Access denied (403) ────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return buildErrorResponse(HttpStatus.FORBIDDEN, "Access denied");
    }

    // ─── Fallback (500) ─────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        logger.error("Unhandled exception", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatus status, String message) {
        ErrorResponse error = new ErrorResponse(
                status.value(),
                message,
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
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

    public record ErrorResponse(int status, String message, String path, LocalDateTime timestamp, String correlationId) {}

    public record ValidationErrorResponse(int status, String message, Map<String, String> errors, String path, LocalDateTime timestamp, String correlationId) {}
}
