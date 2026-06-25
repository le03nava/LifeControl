package com.lifecontrol.api.company.dto;

import com.lifecontrol.api.common.address.dto.AddressRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CompanyRequest DTO Validation Tests")
class CompanyRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @Nested
    @DisplayName("RFC validation")
    class RfcValidationTests {

        @Test
        @DisplayName("should pass validation with valid persona física RFC")
        void validPersonaFisicaRfc_NoViolations() {
            var request = new CompanyRequest(
                    "1", "Test Company", 1, "Test S.A.",
                    "GOML850101XXX", "555-1234", "test@test.com", true,
                    null
            );
            Set<ConstraintViolation<CompanyRequest>> violations = validator.validate(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("should pass validation with valid persona moral RFC")
        void validPersonaMoralRfc_NoViolations() {
            var request = new CompanyRequest(
                    "1", "Test Company", 2, "Test S.A.",
                    "ABC850101XXX", "555-1234", "test@test.com", true,
                    null
            );
            Set<ConstraintViolation<CompanyRequest>> violations = validator.validate(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("should fail validation with invalid RFC")
        void invalidRfc_HasViolation() {
            var request = new CompanyRequest(
                    "1", "Test Company", 1, "Test S.A.",
                    "INVALID123", "555-1234", "test@test.com", true,
                    null
            );
            Set<ConstraintViolation<CompanyRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("rfc"));
        }

        @Test
        @DisplayName("should fail validation with blank RFC")
        void blankRfc_HasViolation() {
            var request = new CompanyRequest(
                    "1", "Test Company", 1, "Test S.A.",
                    "", "555-1234", "test@test.com", true,
                    null
            );
            Set<ConstraintViolation<CompanyRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("rfc"));
        }
    }

    @Nested
    @DisplayName("tipoPersonaId validation")
    class TipoPersonaIdValidationTests {

        @ParameterizedTest
        @DisplayName("should pass validation with valid tipoPersonaId (1-5)")
        @ValueSource(ints = {1, 2, 3, 4, 5})
        void validTipoPersonaId_NoViolations(int tipoPersonaId) {
            var request = new CompanyRequest(
                    "1", "Test Company", tipoPersonaId, "Test S.A.",
                    "GOML850101XXX", "555-1234", "test@test.com", true,
                    null
            );
            Set<ConstraintViolation<CompanyRequest>> violations = validator.validate(request);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("should fail validation with tipoPersonaId less than 1")
        void tipoPersonaIdLessThanOne_HasViolation() {
            var request = new CompanyRequest(
                    "1", "Test Company", 0, "Test S.A.",
                    "GOML850101XXX", "555-1234", "test@test.com", true,
                    null
            );
            Set<ConstraintViolation<CompanyRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("tipoPersonaId"));
        }

        @Test
        @DisplayName("should fail validation with tipoPersonaId greater than 5")
        void tipoPersonaIdGreaterThanFive_HasViolation() {
            var request = new CompanyRequest(
                    "1", "Test Company", 7, "Test S.A.",
                    "GOML850101XXX", "555-1234", "test@test.com", true,
                    null
            );
            Set<ConstraintViolation<CompanyRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("tipoPersonaId"));
        }

        @Test
        @DisplayName("should fail validation with negative tipoPersonaId")
        void negativeTipoPersonaId_HasViolation() {
            var request = new CompanyRequest(
                    "1", "Test Company", -1, "Test S.A.",
                    "GOML850101XXX", "555-1234", "test@test.com", true,
                    null
            );
            Set<ConstraintViolation<CompanyRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("tipoPersonaId"));
        }
    }

    @Nested
    @DisplayName("Standard validation")
    class StandardValidationTests {

        @Test
        @DisplayName("should fail validation when companyName is blank")
        void blankCompanyName_HasViolation() {
            var request = new CompanyRequest(
                    "1", "", 1, "Test S.A.",
                    "GOML850101XXX", "555-1234", "test@test.com", true,
                    null
            );
            Set<ConstraintViolation<CompanyRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("companyName"));
        }

    @Test
    @DisplayName("should fail validation when companyKey is null")
    void nullCompanyKey_HasViolation() {
        var request = new CompanyRequest(
                null, "Test Company", 1, "Test S.A.",
                "GOML850101XXX", "555-1234", "test@test.com", true,
                null
        );
        Set<ConstraintViolation<CompanyRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("companyKey"));
    }

        @Test
        @DisplayName("should fail validation with invalid email format")
        void invalidEmail_HasViolation() {
            var request = new CompanyRequest(
                    "1", "Test Company", 1, "Test S.A.",
                    "GOML850101XXX", "555-1234", "not-an-email", true,
                    null
            );
            Set<ConstraintViolation<CompanyRequest>> violations = validator.validate(request);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("email"));
        }
    }

    @Nested
    @DisplayName("Address field validation — delegated to AddressRequest")
    class AddressFieldValidationTests {

        @Test
        @DisplayName("should pass validation when address is null")
        void nullAddress_NoViolations() {
            var request = new CompanyRequest(
                    "1", "Test Company", 1, "Test S.A.",
                    "GOML850101XXX", "555-1234", "test@test.com", true,
                    null
            );
            Set<ConstraintViolation<CompanyRequest>> violations = validator.validate(request);
            assertThat(violations).isEmpty();
        }
    }
}
