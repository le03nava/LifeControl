package com.lifecontrol.api.exception;

import com.lifecontrol.api.security.exception.ApiUserNotFoundException;
import com.lifecontrol.api.security.exception.DuplicateResourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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
    @DisplayName("handleApiUserNotFound")
    class HandleApiUserNotFoundTests {

        @Test
        @DisplayName("should return 404 with error message")
        void handleApiUserNotFound_Returns404() {
            // Arrange
            String errorMessage = "User not found with id: 123";
            ApiUserNotFoundException exception = new ApiUserNotFoundException(errorMessage);

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = 
                    globalExceptionHandler.handleApiUserNotFound(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(404);
            assertThat(response.getBody().message()).isEqualTo(errorMessage);
            assertThat(response.getBody().timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("handleDuplicateResource")
    class HandleDuplicateResourceTests {

        @Test
        @DisplayName("should return 409 Conflict with error message")
        void handleDuplicateResource_Returns409() {
            // Arrange
            String errorMessage = "Username already exists: testuser";
            DuplicateResourceException exception = new DuplicateResourceException(errorMessage);

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = 
                    globalExceptionHandler.handleDuplicateResource(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(409);
            assertThat(response.getBody().message()).isEqualTo(errorMessage);
            assertThat(response.getBody().timestamp()).isNotNull();
        }
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
        @DisplayName("should return 500 with generic error message")
        void handleGenericException_Returns500() {
            // Arrange
            Exception exception = new RuntimeException("Something went wrong");

            // Act
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = 
                    globalExceptionHandler.handleGenericException(exception);

            // Assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(500);
            assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
            assertThat(response.getBody().timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("ErrorResponse and ValidationErrorResponse Records")
    class ErrorResponseRecordsTests {

        @Test
        @DisplayName("ErrorResponse should have correct structure")
        void errorResponse_HasCorrectStructure() {
            // Arrange
            LocalDateTime timestamp = LocalDateTime.now();

            // Act
            GlobalExceptionHandler.ErrorResponse errorResponse = 
                    new GlobalExceptionHandler.ErrorResponse(404, "Not found", timestamp);

            // Assert
            assertThat(errorResponse.status()).isEqualTo(404);
            assertThat(errorResponse.message()).isEqualTo("Not found");
            assertThat(errorResponse.timestamp()).isEqualTo(timestamp);
        }

        @Test
        @DisplayName("ValidationErrorResponse should have correct structure")
        void validationErrorResponse_HasCorrectStructure() {
            // Arrange
            LocalDateTime timestamp = LocalDateTime.now();
            Map<String, String> errors = Map.of("field1", "error1", "field2", "error2");

            // Act
            GlobalExceptionHandler.ValidationErrorResponse errorResponse = 
                    new GlobalExceptionHandler.ValidationErrorResponse(400, "Validation failed", errors, timestamp);

            // Assert
            assertThat(errorResponse.status()).isEqualTo(400);
            assertThat(errorResponse.message()).isEqualTo("Validation failed");
            assertThat(errorResponse.errors()).isEqualTo(errors);
            assertThat(errorResponse.timestamp()).isEqualTo(timestamp);
        }
    }
}
