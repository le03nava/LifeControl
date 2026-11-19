package com.lifecontrol.api.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shipped defaults of {@code app.security}: loopback and nothing else. A private range is a
 * network, not a proxy declaration, and trusting one made every LAN host able to write its own client
 * address through {@code X-Forwarded-For}.
 */
@DisplayName("SecurityAccessProperties — shipped defaults")
class SecurityAccessPropertiesTest {

    @Test
    @DisplayName("trusts loopback only by default")
    void trustedProxies_defaultToLoopback() {
        assertThat(new SecurityAccessProperties().getTrustedProxies()).containsExactly("127.0.0.1/32", "::1/128");
    }

    @Test
    @DisplayName("allows actuator access from loopback only by default")
    void actuatorAllowedCidrs_defaultToLoopback() {
        assertThat(new SecurityAccessProperties().getActuatorAllowedCidrs()).containsExactly("127.0.0.1/32", "::1/128");
    }
}
