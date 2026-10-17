package com.lifecontrol.api.config.logbook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/**
 * Pins the profile split of {@link SensitiveDataSanitizer#bodyFilter()}.
 * <p>
 * Outside {@code dev} the body must not be emitted at all: the filter answers a fixed
 * redaction instead of the real body, whatever its content type. On {@code dev} the
 * previous behaviour (redact nine JSON string fields, pass everything else through)
 * is preserved.
 * <p>
 * The sanitizer is built through a Spring context so the test can inject the profile
 * without depending on a constructor signature that did not exist before the change.
 */
@DisplayName("SensitiveDataSanitizer body filter by profile")
class SensitiveDataSanitizerProfileTest {

    @Test
    @DisplayName("off dev, a JSON body is replaced by the fixed redaction and no field survives")
    void offDevJsonBodyIsFixedRedaction() {
        var sanitizer = sanitizerFor("prod");

        var result =
                sanitizer.bodyFilter().filter("application/json", "{\"username\":\"john\",\"password\":\"s3cret\"}");

        assertThat(result).isEqualTo("[REDACTED]");
    }

    @Test
    @DisplayName("off dev, a non-JSON body is also replaced by the fixed redaction")
    void offDevNonJsonBodyIsFixedRedaction() {
        var sanitizer = sanitizerFor("staging");

        var result = sanitizer.bodyFilter().filter("text/plain", "plain text that the partial sanitizer would leak");

        assertThat(result).isEqualTo("[REDACTED]");
    }

    @Test
    @DisplayName("the test profile is off dev and redacts the body")
    void testProfileRedactsTheBody() {
        var sanitizer = sanitizerFor("test");

        var result = sanitizer.bodyFilter().filter("application/json", "{\"username\":\"john\"}");

        assertThat(result).isEqualTo("[REDACTED]");
    }

    @Test
    @DisplayName("no active profile also redacts the body")
    void noProfileRedactsTheBody() {
        var sanitizer = sanitizerFor(new String[0]);

        var result = sanitizer.bodyFilter().filter("application/json", "{\"username\":\"john\"}");

        assertThat(result).isEqualTo("[REDACTED]");
    }

    @Test
    @DisplayName("on dev the JSON fields are redacted and the rest of the body is kept")
    void onDevThePreviousBehaviourIsPreserved() {
        var sanitizer = sanitizerFor("dev");

        var result =
                sanitizer.bodyFilter().filter("application/json", "{\"username\":\"john\",\"password\":\"s3cret\"}");

        assertThat(result).contains("\"password\":\"[REDACTED]\"");
        assertThat(result).contains("\"username\":\"john\"");
    }

    private static SensitiveDataSanitizer sanitizerFor(String... activeProfiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.registerBean(SensitiveDataSanitizer.class);
            context.refresh();
            return context.getBean(SensitiveDataSanitizer.class);
        }
    }
}
