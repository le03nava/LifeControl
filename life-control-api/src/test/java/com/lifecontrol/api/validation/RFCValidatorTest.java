package com.lifecontrol.api.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("RFCValidator Tests")
class RFCValidatorTest {

    private RFCValidator validator;

    @Mock
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        validator = new RFCValidator();
    }

    @Nested
    @DisplayName("Valid RFCs")
    class ValidRfcTests {

        @ParameterizedTest
        @DisplayName("should accept valid persona física RFC (4 letters + 6 digits + 3 alphanumeric)")
        @ValueSource(strings = {
                "GOML850101XXX",
                "HEPA880101ABC",
                "MOLA920101XYZ",
                "JIML7501011A2",
                "PERE010101B3C"
        })
        void validPersonaFisica_ReturnsTrue(String rfc) {
            assertThat(validator.isValid(rfc, context)).isTrue();
        }

        @ParameterizedTest
        @DisplayName("should accept valid persona moral RFC (3 letters + 6 digits + 3 alphanumeric)")
        @ValueSource(strings = {
                "ABC850101XXX",
                "XYZ880101ABC",
                "EMP920101XYZ",
                "SERV7501011A2"
        })
        void validPersonaMoral_ReturnsTrue(String rfc) {
            assertThat(validator.isValid(rfc, context)).isTrue();
        }

        @ParameterizedTest
        @DisplayName("should accept RFCs with Ñ character")
        @ValueSource(strings = {"ÑAÑA850101XXX", "NUÑE920101ABC"})
        void validRfcWithSpecialChars_ReturnsTrue(String rfc) {
            assertThat(validator.isValid(rfc, context)).isTrue();
        }

        @Test
        @DisplayName("should accept RFC with & character")
        void validRfcWithAmpersand() {
            assertThat(validator.isValid("&A&L850101XXX", context)).isTrue();
        }

        @Test
        @DisplayName("should accept null value (null is valid per Jakarta convention)")
        void nullValue_ReturnsTrue() {
            assertThat(validator.isValid(null, context)).isTrue();
        }
    }

    @Nested
    @DisplayName("Invalid RFCs")
    class InvalidRfcTests {

        @ParameterizedTest
        @DisplayName("should reject RFC with invalid format")
        @ValueSource(strings = {
                "",
                "INVALID123",
                "SHORT1",
                "1234567890123",
                "ABCD12345",
                "AAAAAA000000AAA"
        })
        void invalidFormat_ReturnsFalse(String rfc) {
            assertThat(validator.isValid(rfc, context)).isFalse();
        }

        @Test
        @DisplayName("should accept RFC with lowercase letters (validator normalizes to uppercase internally)")
        void lowercaseLetters_ReturnsTrue() {
            assertThat(validator.isValid("goml850101xxx", context)).isTrue();
        }

        @Test
        @DisplayName("should reject RFC with special characters")
        void specialChars_ReturnsFalse() {
            assertThat(validator.isValid("GOML@50101XXX", context)).isFalse();
        }

        @Test
        @DisplayName("should reject RFC with spaces")
        void spaces_ReturnsFalse() {
            assertThat(validator.isValid("GOML 850101 XXX", context)).isFalse();
        }

        @Test
        @DisplayName("should reject RFC with fewer than 12 characters")
        void tooShort_ReturnsFalse() {
            assertThat(validator.isValid("ABC850101AB", context)).isFalse();
        }

        @Test
        @DisplayName("should reject RFC with more than 13 characters")
        void tooLong_ReturnsFalse() {
            assertThat(validator.isValid("GOML850101XXXX", context)).isFalse();
        }

        @Test
        @DisplayName("should reject RFC with non-numeric date portion")
        void nonNumericDate_ReturnsFalse() {
            assertThat(validator.isValid("GOMLABCD01XXX", context)).isFalse();
        }
    }
}
