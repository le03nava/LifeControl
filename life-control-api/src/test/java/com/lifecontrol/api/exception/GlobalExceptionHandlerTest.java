package com.lifecontrol.api.exception;

import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.exception.DuplicateCompanyCountryException;
import com.lifecontrol.api.country.exception.CountryNotFoundException;
import com.lifecontrol.api.country.exception.DuplicateCountryException;
import com.lifecontrol.api.purchaseorder.exception.InvalidStatusTransitionException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler Tests")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler globalExceptionHandler;

    @BeforeEach
    void setUp() {
        globalExceptionHandler = new GlobalExceptionHandler();
    }

    @Nested
    @DisplayName("handleValidationErrors")
    class HandleValidationErrorsTests {

        @Test
        @DisplayName("should return 400 with validation errors map")
        void handleValidationErrors_Returns400() {
            // Arrange
            Map<String, String> errors = new HashMap<>();
            errors.put("username", "Username is required");
            errors.put("email", "Email must be valid");

            BindingResult bindingResult = org.mockito.Mockito.mock(BindingResult.class);
            org.mockito.Mockito.when(bindingResult.getFieldErrors()).thenReturn(
                    List.of(
                            new FieldError("apiUserRequest", "username", "Username is required"),
                            new FieldError("apiUserRequest", "email", "Email must be valid")
                    )
            );

            MethodArgumentNotValidException exception =
                    new MethodArgumentNotValidException(null, bindingResult);

            // Act
            ResponseEntity<GlobalExceptionHandler.ValidationErrorResponse> response =
                    globalExceptionHandler.handleValidationErrors(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(400);
            assertThat(response.getBody().message()).isEqualTo("Validation failed");
            assertThat(response.getBody().errors()).containsKey("username");
            assertThat(response.getBody().errors()).containsKey("email");
            assertThat(response.getBody().timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("handleGenericException")
    class HandleGenericExceptionTests {

        @Test
        @DisplayName("should return 500 with generic error message (sanitized)")
        void handleGenericException_Returns500_Sanitized() {
            // Arrange
            Exception exception = new RuntimeException("Something went wrong");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleGenericException(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(500);
            // Message should be generic — no internal details exposed
            assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
            assertThat(response.getBody().timestamp()).isNotNull();
        }

        @Test
        @DisplayName("should not expose exception message or class name in response")
        void handleGenericException_DoesNotExposeInternals() {
            // Arrange
            Exception exception = new RuntimeException("Internal: SQL error on table users");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleGenericException(exception);

            // Assert
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .isEqualTo("An unexpected error occurred")
                    .as("Generic handler must not expose internal exception details");
        }
    }

    @Nested
    @DisplayName("handleNotFound (CountryNotFoundException)")
    class HandleCountryNotFoundTests {

        @Test
        @DisplayName("should return 404 with error message")
        void handleNotFound_Returns404() {
            // Arrange
            CountryNotFoundException exception = new CountryNotFoundException(java.util.UUID.randomUUID());

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleNotFound(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(404);
            assertThat(response.getBody().timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("handleConflict (DuplicateCountryException)")
    class HandleDuplicateCountryTests {

        @Test
        @DisplayName("should return 409 Conflict with error message")
        void handleConflict_Returns409() {
            // Arrange
            DuplicateCountryException exception = new DuplicateCountryException("Country with code 'MX' already exists");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleConflict(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(409);
            assertThat(response.getBody().message()).isEqualTo("Country with code 'MX' already exists");
            assertThat(response.getBody().timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("handleNotFound (CompanyCountryNotFoundException)")
    class HandleCompanyCountryNotFoundTests {

        @Test
        @DisplayName("should return 404 with error message")
        void handleNotFound_Returns404_CompanyCountry() {
            // Arrange
            CompanyCountryNotFoundException exception = new CompanyCountryNotFoundException(java.util.UUID.randomUUID());

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleNotFound(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(404);
            assertThat(response.getBody().timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("handleConflict (DuplicateCompanyCountryException)")
    class HandleDuplicateCompanyCountryTests {

        @Test
        @DisplayName("should return 409 Conflict with error message")
        void handleConflict_Returns409_CompanyCountry() {
            // Arrange
            DuplicateCompanyCountryException exception = new DuplicateCompanyCountryException("MX");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleConflict(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(409);
            assertThat(response.getBody().message()).contains("MX");
            assertThat(response.getBody().timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("handleBadRequest (IllegalArgumentException)")
    class HandleIllegalArgumentTests {

        @Test
        @DisplayName("should return 400 with error message")
        void handleBadRequest_Returns400() {
            // Arrange
            IllegalArgumentException exception = new IllegalArgumentException("Invalid argument");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleBadRequest(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(400);
            assertThat(response.getBody().message()).isEqualTo("Invalid argument");
            assertThat(response.getBody().timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("handleDataIntegrityViolation")
    class HandleDataIntegrityViolationTests {

        @Test
        @DisplayName("should return 409 without exposing SQL details")
        void handleDataIntegrityViolation_Returns409() {
            // Arrange
            var exception = new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"companies_company_key_key\"");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleDataIntegrityViolation(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(409);
            assertThat(response.getBody().message())
                    .doesNotContain("companies_company_key_key")
                    .doesNotContain("duplicate key");
        }
    }

    @Nested
    @DisplayName("handleTypeMismatch")
    class HandleTypeMismatchTests {

        @Test
        @DisplayName("should return 400 mentioning the offending parameter")
        void handleTypeMismatch_Returns400() {
            // Arrange
            var exception = org.mockito.Mockito.mock(MethodArgumentTypeMismatchException.class);
            org.mockito.Mockito.when(exception.getName()).thenReturn("id");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleTypeMismatch(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).contains("id");
        }
    }

    @Nested
    @DisplayName("handleUnreadableBody")
    class HandleUnreadableBodyTests {

        @Test
        @DisplayName("should return 400 for malformed JSON")
        void handleUnreadableBody_Returns400() {
            // Arrange
            var exception = new HttpMessageNotReadableException("Unexpected end-of-input");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleUnreadableBody(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo("Malformed or unreadable request body");
        }
    }

    @Nested
    @DisplayName("handleMissingParameter")
    class HandleMissingParameterTests {

        @Test
        @DisplayName("should return 400 mentioning the missing parameter")
        void handleMissingParameter_Returns400() {
            // Arrange
            var exception = new MissingServletRequestParameterException("size", "int");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleMissingParameter(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).contains("size");
        }
    }

    @Nested
    @DisplayName("handleConstraintViolation")
    class HandleConstraintViolationTests {

        @Test
        @DisplayName("should return 400 with per-parameter errors map")
        void handleConstraintViolation_Returns400() {
            // Arrange
            @SuppressWarnings("unchecked")
            ConstraintViolation<Object> violation = org.mockito.Mockito.mock(ConstraintViolation.class);
            Path path = org.mockito.Mockito.mock(Path.class);
            org.mockito.Mockito.when(path.toString()).thenReturn("getById.id");
            org.mockito.Mockito.when(violation.getPropertyPath()).thenReturn(path);
            org.mockito.Mockito.when(violation.getMessage()).thenReturn("must be a valid UUID");

            var exception = new ConstraintViolationException(Set.of(violation));

            // Act
            ResponseEntity<GlobalExceptionHandler.ValidationErrorResponse> response =
                    globalExceptionHandler.handleConstraintViolation(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).isEqualTo("Validation failed");
            assertThat(response.getBody().errors()).containsEntry("getById.id", "must be a valid UUID");
        }
    }

    @Nested
    @DisplayName("handleMethodNotSupported")
    class HandleMethodNotSupportedTests {

        @Test
        @DisplayName("should return 405 mentioning the HTTP method")
        void handleMethodNotSupported_Returns405() {
            // Arrange
            var exception = new HttpRequestMethodNotSupportedException("PATCH");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleMethodNotSupported(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(405);
            assertThat(response.getBody().message()).contains("PATCH");
        }
    }

    @Nested
    @DisplayName("ErrorResponse and ValidationErrorResponse Records")
    class ErrorResponseRecordsTests {

        @Test
        @DisplayName("ErrorResponse should have correct structure with path and correlationId")
        void errorResponse_HasCorrectStructure() {
            // Arrange
            LocalDateTime timestamp = LocalDateTime.now();

            // Act
            GlobalExceptionHandler.ErrorResponse errorResponse =
                    new GlobalExceptionHandler.ErrorResponse(404, "Not found", "/api/test", timestamp, "trace-123");

            // Assert
            assertThat(errorResponse.status()).isEqualTo(404);
            assertThat(errorResponse.message()).isEqualTo("Not found");
            assertThat(errorResponse.path()).isEqualTo("/api/test");
            assertThat(errorResponse.timestamp()).isEqualTo(timestamp);
            assertThat(errorResponse.correlationId()).isEqualTo("trace-123");
        }

        @Test
        @DisplayName("ErrorResponse should accept null path and correlationId")
        void errorResponse_AcceptsNullPathAndCorrelationId() {
            // Arrange
            LocalDateTime timestamp = LocalDateTime.now();

            // Act
            GlobalExceptionHandler.ErrorResponse errorResponse =
                    new GlobalExceptionHandler.ErrorResponse(500, "Error", null, timestamp, null);

            // Assert
            assertThat(errorResponse.status()).isEqualTo(500);
            assertThat(errorResponse.path()).isNull();
            assertThat(errorResponse.correlationId()).isNull();
        }

        @Test
        @DisplayName("ValidationErrorResponse should have correct structure")
        void validationErrorResponse_HasCorrectStructure() {
            // Arrange
            LocalDateTime timestamp = LocalDateTime.now();
            Map<String, String> errors = Map.of("field1", "error1", "field2", "error2");

            // Act
            GlobalExceptionHandler.ValidationErrorResponse errorResponse =
                    new GlobalExceptionHandler.ValidationErrorResponse(400, "Validation failed", errors, "/api/companies", timestamp, "trace-456");

            // Assert
            assertThat(errorResponse.status()).isEqualTo(400);
            assertThat(errorResponse.message()).isEqualTo("Validation failed");
            assertThat(errorResponse.errors()).isEqualTo(errors);
            assertThat(errorResponse.path()).isEqualTo("/api/companies");
            assertThat(errorResponse.timestamp()).isEqualTo(timestamp);
            assertThat(errorResponse.correlationId()).isEqualTo("trace-456");
        }
    }

    @Nested
    @DisplayName("generic exception hierarchy")
    class GenericExceptionHierarchyTests {

        @Test
        @DisplayName("ResourceNotFoundException builds its message from resource class and id")
        void resourceNotFound_BuildsMessageFromResourceAndId() {
            // Arrange
            var id = java.util.UUID.randomUUID();

            // Act
            var exception = new ResourceNotFoundException(Company.class, id);

            // Assert
            assertThat(exception.getMessage())
                    .isEqualTo("Company not found with id: " + id);
        }

        @Test
        @DisplayName("domain exceptions extend the generic category bases")
        void domainExceptions_ExtendGenericCategories() {
            assertThat(new CompanyNotFoundException(java.util.UUID.randomUUID()))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(new DuplicateCountryException("duplicate"))
                    .isInstanceOf(DuplicateResourceException.class);
            assertThat(new InvalidStatusTransitionException("Draft", "Received"))
                    .isInstanceOf(ConflictException.class);
        }
    }
}
