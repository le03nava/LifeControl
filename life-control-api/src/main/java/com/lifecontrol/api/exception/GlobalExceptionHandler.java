package com.lifecontrol.api.exception;

import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyCountryException;
import com.lifecontrol.api.company.exception.DuplicateCompanyException;
import com.lifecontrol.api.company.exception.DuplicateCompanyRegionException;
import com.lifecontrol.api.company.exception.CompanyZoneNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyZoneException;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.exception.DuplicateCompanyStoreException;
import com.lifecontrol.api.country.exception.CountryNotFoundException;
import com.lifecontrol.api.country.exception.DuplicateCountryException;
import com.lifecontrol.api.product.exception.DuplicateProductException;
import com.lifecontrol.api.product.exception.ProductNotFoundException;
import com.lifecontrol.api.product.supplier.exception.DuplicateProductSupplierException;
import com.lifecontrol.api.product.supplier.exception.ProductSupplierNotFoundException;
import com.lifecontrol.api.supplier.exception.DuplicateSupplierException;
import com.lifecontrol.api.supplier.exception.SupplierNotFoundException;
import com.lifecontrol.api.status.exception.DuplicateStatusException;
import com.lifecontrol.api.status.exception.DuplicateStatusTypeException;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.exception.StatusTypeNotFoundException;
import com.lifecontrol.api.paymentmethod.exception.DuplicatePaymentMethodException;
import com.lifecontrol.api.paymentmethod.exception.PaymentMethodNotFoundException;
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

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DuplicateCompanyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateCompany(DuplicateCompanyException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(CompanyNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCompanyNotFound(CompanyNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(CountryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCountryNotFound(CountryNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(DuplicateCountryException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateCountry(DuplicateCountryException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductNotFound(ProductNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(DuplicateProductException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateProduct(DuplicateProductException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(ProductSupplierNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductSupplierNotFound(ProductSupplierNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(DuplicateProductSupplierException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateProductSupplier(DuplicateProductSupplierException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(SupplierNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSupplierNotFound(SupplierNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(DuplicateSupplierException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateSupplier(DuplicateSupplierException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(CompanyCountryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCompanyCountryNotFound(CompanyCountryNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(CompanyRegionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCompanyRegionNotFound(CompanyRegionNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(DuplicateCompanyRegionException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateCompanyRegion(DuplicateCompanyRegionException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(CompanyZoneNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCompanyZoneNotFound(CompanyZoneNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(DuplicateCompanyZoneException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateCompanyZone(DuplicateCompanyZoneException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(CompanyStoreNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCompanyStoreNotFound(CompanyStoreNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(DuplicateCompanyStoreException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateCompanyStore(DuplicateCompanyStoreException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(PaymentMethodNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentMethodNotFound(PaymentMethodNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(DuplicatePaymentMethodException.class)
    public ResponseEntity<ErrorResponse> handleDuplicatePaymentMethod(DuplicatePaymentMethodException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(DuplicateCompanyCountryException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateCompanyCountry(DuplicateCompanyCountryException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(StatusTypeNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleStatusTypeNotFound(StatusTypeNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(DuplicateStatusTypeException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateStatusType(DuplicateStatusTypeException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(StatusNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleStatusNotFound(StatusNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(DuplicateStatusException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateStatus(DuplicateStatusException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(IdentityProviderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleIdentityProviderNotFound(IdentityProviderNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(IdentityProviderConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdentityProviderConflict(IdentityProviderConflictException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(IdentityProviderConnectionException.class)
    public ResponseEntity<ErrorResponse> handleIdentityProviderConnection(IdentityProviderConnectionException ex) {
        logger.error("Identity provider connection failure", ex);
        ErrorResponse error = new ErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Identity provider temporarily unavailable",
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

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

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Access denied",
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        logger.error("Unhandled exception", ex);
        ErrorResponse error = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred",
                getCurrentPath(),
                LocalDateTime.now(),
                getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
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
