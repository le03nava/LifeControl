package com.lifecontrol.api.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Pins the one thing the shipped-default test cannot see: that the key the properties file declares
 * is the key that actually reaches the field.
 *
 * <p>Measured before the fix (issue #979): the file declared {@code
 * app.security.actuator.allowed-cidrs} while the field is {@code actuatorAllowedCidrs}, which relaxed
 * binding only fills from {@code app.security.actuator-allowed-cidrs}. The property was therefore
 * ignored in silence and the field's Java default was what the actuator gate read. It stayed invisible
 * for as long as the default and the file carried the same list, and it surfaced as a refusal when
 * {@code odd/tasks/api-edge-trust.md} narrowed the default to loopback: the deployment declared
 * {@code 172.16.0.0/12}, the container sat on {@code 172.18.0.0/16}, and {@code /actuator/prometheus}
 * answered 401.</p>
 *
 * <p>This test loads the real {@code application.properties} rather than restating the key, so a future
 * rename of either side is a red build instead of a dead knob.</p>
 */
@DisplayName("SecurityAccessProperties binding")
class SecurityAccessPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(UserConfigurations.of(BindingConfiguration.class));

    @Test
    @DisplayName("binds the actuator allow-list declared through the operator's environment variable")
    void bindsTheOperatorVariableToTheActuatorAllowList() {
        runner.withPropertyValues("ACTUATOR_ALLOWED_CIDRS=172.16.0.0/12")
                .run(context -> assertThat(
                                context.getBean(SecurityAccessProperties.class).getActuatorAllowedCidrs())
                        .containsExactly("172.16.0.0/12"));
    }

    @Test
    @DisplayName("binds the trusted-proxy list through the same variable, so one knob feeds both")
    void bindsTheOperatorVariableToTheTrustedProxies() {
        runner.withPropertyValues("TRUSTED_PROXIES=172.16.0.0/12")
                .run(context -> assertThat(
                                context.getBean(SecurityAccessProperties.class).getTrustedProxies())
                        .containsExactly("172.16.0.0/12"));
    }

    @Test
    @DisplayName("keeps loopback when the operator declares nothing")
    void keepsLoopbackWhenNothingIsDeclared() {
        runner.run(context -> assertThat(
                        context.getBean(SecurityAccessProperties.class).getActuatorAllowedCidrs())
                .containsExactly("127.0.0.1/32", "::1/128"));
    }

    @Configuration
    @EnableConfigurationProperties(SecurityAccessProperties.class)
    static class BindingConfiguration {}
}
