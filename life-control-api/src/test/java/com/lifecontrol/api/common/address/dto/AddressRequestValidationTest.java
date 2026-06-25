package com.lifecontrol.api.common.address.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AddressRequest DTO Validation Tests")
class AddressRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @Nested
    @DisplayName("Null field validation")
    class NullFieldValidationTests {

        @Test
        @DisplayName("should pass validation when all fields are null")
        void allNull_NoViolations() {
            var request = new AddressRequest(null, null, null, null, null, null, null, null);
            Set<ConstraintViolation<AddressRequest>> violations = validator.validate(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("should pass validation when countryId is null")
        void nullCountryId_NoViolations() {
            var request = new AddressRequest("Calle", "123", null, "Centro", "12345", "City", "State", null);
            Set<ConstraintViolation<AddressRequest>> violations = validator.validate(request);
            assertThat(violations).isEmpty();
        }
    }

    @Nested
    @DisplayName("@Size validation")
    class SizeValidationTests {

        @Test
        @DisplayName("should fail validation when street exceeds 255 characters")
        void streetTooLong_HasViolation() {
            var request = new AddressRequest(
                    "A".repeat(256), null, null, null, null, null, null, null
            );
            Set<ConstraintViolation<AddressRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("street"));
        }

        @Test
        @DisplayName("should pass validation when street is exactly 255 characters")
        void streetAtMaxLength_NoViolations() {
            var request = new AddressRequest(
                    "A".repeat(255), null, null, null, null, null, null, null
            );
            Set<ConstraintViolation<AddressRequest>> violations = validator.validate(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("should fail validation when streetNumber exceeds 20 characters")
        void streetNumberTooLong_HasViolation() {
            var request = new AddressRequest(
                    null, "B".repeat(21), null, null, null, null, null, null
            );
            Set<ConstraintViolation<AddressRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("streetNumber"));
        }

        @Test
        @DisplayName("should fail validation when internalNumber exceeds 20 characters")
        void internalNumberTooLong_HasViolation() {
            var request = new AddressRequest(
                    null, null, "C".repeat(21), null, null, null, null, null
            );
            Set<ConstraintViolation<AddressRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("internalNumber"));
        }

        @Test
        @DisplayName("should fail validation when neighborhood exceeds 255 characters")
        void neighborhoodTooLong_HasViolation() {
            var request = new AddressRequest(
                    null, null, null, "D".repeat(256), null, null, null, null
            );
            Set<ConstraintViolation<AddressRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("neighborhood"));
        }

        @Test
        @DisplayName("should fail validation when zipCode exceeds 20 characters")
        void zipCodeTooLong_HasViolation() {
            var request = new AddressRequest(
                    null, null, null, null, "E".repeat(21), null, null, null
            );
            Set<ConstraintViolation<AddressRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("zipCode"));
        }

        @Test
        @DisplayName("should fail validation when city exceeds 255 characters")
        void cityTooLong_HasViolation() {
            var request = new AddressRequest(
                    null, null, null, null, null, "F".repeat(256), null, null
            );
            Set<ConstraintViolation<AddressRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("city"));
        }

        @Test
        @DisplayName("should fail validation when state exceeds 255 characters")
        void stateTooLong_HasViolation() {
            var request = new AddressRequest(
                    null, null, null, null, null, null, "G".repeat(256), null
            );
            Set<ConstraintViolation<AddressRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("state"));
        }
    }

    @Nested
    @DisplayName("Valid address validation")
    class ValidAddressTests {

        @Test
        @DisplayName("should pass validation with all valid fields")
        void allValid_NoViolations() {
            var request = new AddressRequest(
                    "Calle Principal", "123", "A-101", "Centro",
                    "06600", "Ciudad de México", "CDMX", UUID.randomUUID()
            );
            Set<ConstraintViolation<AddressRequest>> violations = validator.validate(request);
            assertThat(violations).isEmpty();
        }
    }
}
