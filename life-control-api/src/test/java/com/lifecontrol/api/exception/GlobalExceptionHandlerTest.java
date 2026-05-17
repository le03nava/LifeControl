package com.lifecontrol.api.exception;

import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyCountryException;
import com.lifecontrol.api.country.exception.CountryNotFoundException;
import com.lifecontrol.api.country.exception.DuplicateCountryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    @DisplayName("handleCountryNotFound")
    class HandleCountryNotFoundTests {

        @Test
        @DisplayName("should return 404 with error message")
        void handleCountryNotFound_Returns404() {
            // Arrange
            CountryNotFoundException exception = new CountryNotFoundException(java.util.UUID.randomUUID());

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleCountryNotFound(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(404);
            assertThat(response.getBody().timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("handleDuplicateCountry")
    class HandleDuplicateCountryTests {

        @Test
        @DisplayName("should return 409 Conflict with error message")
        void handleDuplicateCountry_Returns409() {
            // Arrange
            DuplicateCountryException exception = new DuplicateCountryException("Ya existe un país con código: MX");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleDuplicateCountry(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(409);
            assertThat(response.getBody().message()).isEqualTo("Ya existe un país con código: MX");
            assertThat(response.getBody().timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("handleCompanyCountryNotFound")
    class HandleCompanyCountryNotFoundTests {

        @Test
        @DisplayName("should return 404 with error message")
        void handleCompanyCountryNotFound_Returns404() {
            // Arrange
            CompanyCountryNotFoundException exception = new CompanyCountryNotFoundException(java.util.UUID.randomUUID());

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleCompanyCountryNotFound(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(404);
            assertThat(response.getBody().timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("handleDuplicateCompanyCountry")
    class HandleDuplicateCompanyCountryTests {

        @Test
        @DisplayName("should return 409 Conflict with error message")
        void handleDuplicateCompanyCountry_Returns409() {
            // Arrange
            DuplicateCompanyCountryException exception = new DuplicateCompanyCountryException("MX");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleDuplicateCompanyCountry(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(409);
            assertThat(response.getBody().message()).contains("MX");
            assertThat(response.getBody().timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("handleIllegalArgument")
    class HandleIllegalArgumentTests {

        @Test
        @DisplayName("should return 400 with error message")
        void handleIllegalArgument_Returns400() {
            // Arrange
            IllegalArgumentException exception = new IllegalArgumentException("Invalid argument");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                    globalExceptionHandler.handleIllegalArgument(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(400);
            assertThat(response.getBody().message()).isEqualTo("Invalid argument");
            assertThat(response.getBody().timestamp()).isNotNull();
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
}
