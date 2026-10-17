package com.lifecontrol.api.config.logbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Pins the fail-closed profile guard as the context sees it.
 * <p>
 * The unit closes the footgun where {@code spring.profiles.active} defaults to {@code dev}
 * (application.properties) and every deployment that forgot the variable logs every
 * exchange in full. The guard refuses to start exactly in that state — effective profile
 * {@code dev} while {@code SPRING_PROFILES_ACTIVE} was never declared — and names the
 * variable. Declaring the variable, or running under any other profile, boots as before.
 * <p>
 * Each case gets a controlled environment so the assertion does not depend on the JVM the
 * suite happens to run in; the {@code test} profile is pinned for the suite in
 * {@code build.gradle}, which is why the guard and the harness are one change.
 */
@DisplayName("fail-closed dev profile guard")
class LogbookProfileGuardTest {

    @Configuration(proxyBeanMethods = false)
    static class EmptyApplication {}

    @Test
    @DisplayName("refuses to start when dev is effective and SPRING_PROFILES_ACTIVE was never declared")
    void undeclaredDevRefusesToStart() {
        var thrown = catchThrowable(() -> run(environment(Map.of(), Map.of())));

        assertThat(thrown)
                .as("the context must not start on the built-in dev default")
                .isNotNull();
        assertThat(messagesOf(thrown))
                .as("the failure has to name the variable the operator must declare")
                .contains("SPRING_PROFILES_ACTIVE");
    }

    @Test
    @DisplayName("boots when SPRING_PROFILES_ACTIVE is declared as dev")
    void declaredDevBoots() {
        try (var context = run(environment(Map.of("SPRING_PROFILES_ACTIVE", "dev"), Map.of()))) {
            assertThat(context.isActive()).isTrue();
        }
    }

    @Test
    @DisplayName("is not affected by the test profile")
    void testProfileBoots() {
        try (var context = run(environment(Map.of(), Map.of("spring.profiles.active", "test")))) {
            assertThat(context.isActive()).isTrue();
        }
    }

    @Test
    @DisplayName("is not affected by a non-dev profile")
    void stagingProfileBoots() {
        try (var context = run(environment(Map.of(), Map.of("spring.profiles.active", "staging")))) {
            assertThat(context.isActive()).isTrue();
        }
    }

    private static ConfigurableApplicationContext run(ConfigurableEnvironment environment) {
        var application = new SpringApplication(EmptyApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setEnvironment(environment);
        return application.run();
    }

    /**
     * A deterministic environment: the real JVM properties and variables are replaced so a
     * machine that happens to have {@code SPRING_PROFILES_ACTIVE} exported cannot change the
     * verdict, and the guard still reads the {@code systemEnvironment} property source it
     * reads in production.
     */
    private static ConfigurableEnvironment environment(
            Map<String, String> environmentVariables, Map<String, Object> systemProperties) {
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.copyOf(environmentVariables)));
        environment
                .getPropertySources()
                .replace(
                        StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                        new MapPropertySource(
                                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                                Map.copyOf(systemProperties)));
        return environment;
    }

    private static String messagesOf(Throwable thrown) {
        var messages = new StringBuilder();
        for (var current = thrown; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }
}
