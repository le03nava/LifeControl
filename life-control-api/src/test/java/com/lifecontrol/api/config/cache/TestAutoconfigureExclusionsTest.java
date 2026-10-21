package com.lifecontrol.api.config.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that every auto-configuration the test profile excludes is one the test classpath actually
 * provides.
 *
 * <p>An exclusion naming a class nothing ships removes nothing, and the configuration it means to
 * disable stays active: the file keeps its comment, the line keeps its shape, and the only symptom
 * is a bean in every context that nobody asked for. The test profile carried exactly that after the
 * Boot 4 packages moved — `org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration`
 * was still named while Boot 4.1.1 offers `org.springframework.boot.data.redis.autoconfigure
 * .DataRedisAutoConfiguration` — and nothing read the exclusion back, so the Redis auto-configuration
 * ran in every test context for as long as the rename existed. The defect is recorded as the first
 * bullet of `L10` in `odd/tasks/redis-cache-activation.md` and reaffirmed in its `L14`.
 *
 * <p>This is a classpath assertion rather than a context assertion on purpose: it measures the one
 * thing the file can get wrong on its own, needs no Redis and no database, and fails the moment a
 * future upgrade renames the class again instead of waiting for a bean to be missed.
 */
@DisplayName("test profile's excluded auto-configurations")
class TestAutoconfigureExclusionsTest {

    @Test
    @DisplayName("every excluded auto-configuration is on the test classpath")
    void everyExcludedAutoConfigurationIsOnTheTestClasspath() throws IOException {
        var excluded = testProperties().getProperty("spring.autoconfigure.exclude");

        assertThat(excluded)
                .as("application-test.properties must declare spring.autoconfigure.exclude: the test "
                        + "profile has no Redis, and a missing key would look like an intentional default")
                .isNotNull()
                .isNotBlank();

        for (String entry : excluded.split(",")) {
            String className = entry.trim();
            assertThat(className)
                    .as("an empty entry in spring.autoconfigure.exclude excludes nothing and hides a typo")
                    .isNotEmpty();

            assertThatCode(() -> Class.forName(className, false, getClass().getClassLoader()))
                    .as(
                            "%s is named in spring.autoconfigure.exclude but is not on the test classpath: "
                                    + "an exclusion that names a class nothing provides removes nothing, and the "
                                    + "auto-configuration this file means to disable stays active",
                            className)
                    .doesNotThrowAnyException();
        }
    }

    /** Reads the test profile's own properties from the classpath, which is what the suite loads. */
    private static Properties testProperties() throws IOException {
        var properties = new Properties();
        try (InputStream in =
                TestAutoconfigureExclusionsTest.class.getResourceAsStream("/application-test.properties")) {
            if (in == null) {
                throw new IllegalStateException(
                        "application-test.properties is not on the test classpath; this test must read "
                                + "the shipped file");
            }
            properties.load(in);
        }
        return properties;
    }
}
