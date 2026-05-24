package com.lifecontrol.api.company.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CompanyRegion DTO Validation Tests")
class CompanyRegionDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Nested
    @DisplayName("CreateCompanyRegionRequest validation")
    class CreateRequestValidationTests {

        @Test
        @DisplayName("should pass validation with valid fields")
        void validRequest_NoViolations() {
            var request = new CreateCompanyRegionRequest("NORTE", "Norte");
            Set<ConstraintViolation<CreateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("should fail validation when regionCode is blank")
        void blankRegionCode_HasViolation() {
            var request = new CreateCompanyRegionRequest("", "Norte");
            Set<ConstraintViolation<CreateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("regionCode"));
        }

        @Test
        @DisplayName("should fail validation when regionCode is null")
        void nullRegionCode_HasViolation() {
            var request = new CreateCompanyRegionRequest(null, "Norte");
            Set<ConstraintViolation<CreateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("regionCode"));
        }

        @Test
        @DisplayName("should fail validation when regionCode exceeds 10 characters")
        void oversizedRegionCode_HasViolation() {
            var request = new CreateCompanyRegionRequest("TOO_LONG_CODE", "Norte");
            Set<ConstraintViolation<CreateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("regionCode"));
        }

        @Test
        @DisplayName("should fail validation when regionName is blank")
        void blankRegionName_HasViolation() {
            var request = new CreateCompanyRegionRequest("NORTE", "");
            Set<ConstraintViolation<CreateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("regionName"));
        }

        @Test
        @DisplayName("should fail validation when regionName is null")
        void nullRegionName_HasViolation() {
            var request = new CreateCompanyRegionRequest("NORTE", null);
            Set<ConstraintViolation<CreateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("regionName"));
        }

        @Test
        @DisplayName("should fail validation when regionName exceeds 100 characters")
        void oversizedRegionName_HasViolation() {
            var hundredOneChars = "a".repeat(101);
            var request = new CreateCompanyRegionRequest("NORTE", hundredOneChars);
            Set<ConstraintViolation<CreateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("regionName"));
        }

        @Test
        @DisplayName("should fail with both violations when all fields are invalid")
        void allFieldsInvalid_HasMultipleViolations() {
            var request = new CreateCompanyRegionRequest("", "");
            Set<ConstraintViolation<CreateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).hasSize(2);
        }
    }

    @Nested
    @DisplayName("UpdateCompanyRegionRequest validation")
    class UpdateRequestValidationTests {

        @Test
        @DisplayName("should pass validation with valid fields")
        void validRequest_NoViolations() {
            var request = new UpdateCompanyRegionRequest("SUR", "Sur");
            Set<ConstraintViolation<UpdateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("should fail validation when regionCode is blank")
        void blankRegionCode_HasViolation() {
            var request = new UpdateCompanyRegionRequest("", "Sur");
            Set<ConstraintViolation<UpdateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("regionCode"));
        }

        @Test
        @DisplayName("should fail validation when regionCode is null")
        void nullRegionCode_HasViolation() {
            var request = new UpdateCompanyRegionRequest(null, "Sur");
            Set<ConstraintViolation<UpdateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("regionCode"));
        }

        @Test
        @DisplayName("should fail validation when regionCode exceeds 10 characters")
        void oversizedRegionCode_HasViolation() {
            var request = new UpdateCompanyRegionRequest("TOO_LONG_CODE", "Sur");
            Set<ConstraintViolation<UpdateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("regionCode"));
        }

        @Test
        @DisplayName("should fail validation when regionName is blank")
        void blankRegionName_HasViolation() {
            var request = new UpdateCompanyRegionRequest("SUR", "");
            Set<ConstraintViolation<UpdateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("regionName"));
        }

        @Test
        @DisplayName("should fail validation when regionName is null")
        void nullRegionName_HasViolation() {
            var request = new UpdateCompanyRegionRequest("SUR", null);
            Set<ConstraintViolation<UpdateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("regionName"));
        }

        @Test
        @DisplayName("should fail validation when regionName exceeds 100 characters")
        void oversizedRegionName_HasViolation() {
            var hundredOneChars = "a".repeat(101);
            var request = new UpdateCompanyRegionRequest("SUR", hundredOneChars);
            Set<ConstraintViolation<UpdateCompanyRegionRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("regionName"));
        }
    }
}
