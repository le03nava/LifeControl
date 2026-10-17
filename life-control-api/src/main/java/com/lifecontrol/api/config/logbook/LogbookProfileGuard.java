package com.lifecontrol.api.config.logbook;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Fail-closed guard for the {@code dev} profile, registered in
 * {@code META-INF/spring.factories}.
 * <p>
 * {@code application.properties} defaults {@code spring.profiles.active} to {@code dev}, and
 * the {@code dev} profile logs every HTTP exchange with its body. That makes a deployment (or a
 * local run) that forgot the variable indistinguishable from one that deliberately chose
 * {@code dev}, and the failure is silent. This guard refuses to start exactly in that state:
 * the effective profile is {@code dev} while {@code SPRING_PROFILES_ACTIVE} was never declared.
 * The message names the variable, so the fix is in the failure itself.
 * <p>
 * It runs after configuration data has been processed, so the profile it observes is the
 * effective one, and it only inspects the environment the process actually runs in. Declaring
 * the variable as {@code dev}, or activating any other profile, starts normally. The test suite
 * declares its own profile in {@code build.gradle}, which is why the guard and the harness are
 * one change.
 */
public class LogbookProfileGuard implements EnvironmentPostProcessor, Ordered {

    /** The variable a {@code dev} run has to declare so its choice is explicit, not assumed. */
    static final String PROFILE_VARIABLE = "SPRING_PROFILES_ACTIVE";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.matchesProfiles("dev")) {
            return;
        }
        if (isProfileVariableDeclared(environment)) {
            return;
        }
        throw new IllegalStateException("Refusing to start: the effective Spring profile is 'dev' but the "
                + "environment variable " + PROFILE_VARIABLE + " was never declared. Declare it explicitly "
                + "(for example " + PROFILE_VARIABLE + "=dev for local development, or staging/prod in a "
                + "deployment), or activate a non-dev profile. Falling back to the built-in 'dev' default "
                + "would silently enable full request/response body logging.");
    }

    /**
     * A declared variable is the one the operator set, and it is what distinguishes a deliberate
     * {@code dev} run from a process that merely picked up the default. Only the process
     * environment counts: a system property or a command-line argument is a different declaration
     * and is deliberately not accepted here.
     */
    private static boolean isProfileVariableDeclared(ConfigurableEnvironment environment) {
        PropertySource<?> systemEnvironment =
                environment.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return systemEnvironment != null && systemEnvironment.containsProperty(PROFILE_VARIABLE);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
