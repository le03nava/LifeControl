package com.lifecontrol.api.config.logbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.zalando.logbook.HttpHeaders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SensitiveDataSanitizer Tests")
class SensitiveDataSanitizerTest {

    private SensitiveDataSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new SensitiveDataSanitizer();
    }

    @Nested
    @DisplayName("authorizationHeaderFilter")
    class AuthorizationHeaderFilterTests {

        @Test
        @DisplayName("should redact Authorization header value")
        void shouldRedactAuthorizationHeader() {
            // Logbook's authorization() filter matches "Authorization" (standard HTTP case)
            // and replaces the value with "XXX" (Logbook's built-in replacement)
            var headers = HttpHeaders.of(Map.of(
                    "Authorization", List.of("Bearer eyJhbGciOiJIUzI1NiJ9.test")
            ));

            var result = sanitizer.authorizationHeaderFilter().filter(headers);

            assertThat(result.get("Authorization"))
                    .containsExactly("XXX");
        }

        @Test
        @DisplayName("should not modify other headers when using authorization filter")
        void shouldNotModifyOtherHeaders() {
            var headers = HttpHeaders.of(Map.of(
                    "Authorization", List.of("Bearer token123"),
                    "Content-Type", List.of("application/json")
            ));

            var result = sanitizer.authorizationHeaderFilter().filter(headers);

            assertThat(result.get("Content-Type"))
                    .containsExactly("application/json");
        }
    }

    @Nested
    @DisplayName("sensitiveHeadersFilter")
    class SensitiveHeadersFilterTests {

        @Test
        @DisplayName("should redact sensitive headers (cookies, CSRF tokens, API keys)")
        void shouldRedactSensitiveHeaders() {
            // Arrange
            var headers = HttpHeaders.of(Map.of(
                    "Cookie", List.of("sessionId=abc123"),
                    "X-CSRF-Token", List.of("csrf-token-value"),
                    "X-Api-Key", List.of("secret-api-key-123")
            ));

            // Act
            var result = sanitizer.sensitiveHeadersFilter().filter(headers);

            // Assert
            assertThat(result.get("Cookie")).containsExactly("[REDACTED]");
            assertThat(result.get("X-CSRF-Token")).containsExactly("[REDACTED]");
            assertThat(result.get("X-Api-Key")).containsExactly("[REDACTED]");
        }

        @Test
        @DisplayName("should not redact non-sensitive headers")
        void shouldNotRedactNonSensitiveHeaders() {
            // Arrange
            var headers = HttpHeaders.of(Map.of(
                    "Content-Type", List.of("application/json"),
                    "Accept", List.of("*/*")
            ));

            // Act
            var result = sanitizer.sensitiveHeadersFilter().filter(headers);

            // Assert
            assertThat(result.get("Content-Type")).containsExactly("application/json");
            assertThat(result.get("Accept")).containsExactly("*/*");
        }
    }

    @Nested
    @DisplayName("bodyFilter")
    class BodyFilterTests {

        @Test
        @DisplayName("should redact password field value in JSON body")
        void shouldRedactPasswordField() {
            var contentType = "application/json";
            var body = "{\"username\":\"john\",\"password\":\"supersecret123\"}";

            var result = sanitizer.bodyFilter().filter(contentType, body);

            assertThat(result).contains("\"password\":\"[REDACTED]\"");
            assertThat(result).contains("\"username\":\"john\"");
        }

        @Test
        @DisplayName("should redact token field value in JSON body")
        void shouldRedactTokenField() {
            var contentType = "application/json";
            var body = "{\"access_token\":\"eyJhbGciOiJIUzI1NiJ9.test\",\"scope\":\"read\"}";

            var result = sanitizer.bodyFilter().filter(contentType, body);

            assertThat(result).contains("\"access_token\":\"[REDACTED]\"");
            assertThat(result).contains("\"scope\":\"read\"");
        }

        @Test
        @DisplayName("should redact secret and API key fields in JSON body")
        void shouldRedactSecretAndApiKeyFields() {
            var contentType = "application/json";
            var body = "{\"client_secret\":\"my-client-secret\",\"api_key\":\"key-123\"}";

            var result = sanitizer.bodyFilter().filter(contentType, body);

            assertThat(result).contains("\"client_secret\":\"[REDACTED]\"");
            assertThat(result).contains("\"api_key\":\"[REDACTED]\"");
        }

        @Test
        @DisplayName("should leave non-sensitive JSON fields unchanged")
        void shouldKeepNonSensitiveFields() {
            var contentType = "application/json";
            var body = "{\"username\":\"john\",\"email\":\"john@example.com\"}";

            var result = sanitizer.bodyFilter().filter(contentType, body);

            assertThat(result).contains("\"username\":\"john\"");
            assertThat(result).contains("\"email\":\"john@example.com\"");
        }

        @Test
        @DisplayName("should pass through non-JSON content unchanged")
        void shouldPassThroughNonJsonContent() {
            var contentType = "text/plain";
            var body = "plain text content";

            var result = sanitizer.bodyFilter().filter(contentType, body);

            assertThat(result).isEqualTo("plain text content");
        }
    }
}
