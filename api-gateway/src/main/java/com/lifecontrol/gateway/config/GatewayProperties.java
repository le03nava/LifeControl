package com.lifecontrol.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound configuration of the gateway.
 *
 * <p>The two list components are copied and made unmodifiable at construction: the binder hands in a mutable list
 * that it keeps for itself, and every consumer of these values — a JWT validator, the CORS configuration — would
 * otherwise share one list with the bound configuration, so a single {@code add} anywhere would rewrite the
 * allowlist the gateway validates against while it is running.
 */
@Validated
@ConfigurationProperties(prefix = "lifecontrol.gateway")
public record GatewayProperties(
        @NotBlank String lifeControlApiUri,
        @NotBlank String keycloakInternalUri,
        @NotEmpty List<String> allowedIssuers,
        @NotEmpty List<String> allowedOrigins) {

    public GatewayProperties {
        // `copyOf` refuses a null element, which is the shape a typo in the properties file would take;
        // an absent list stays null so that `@NotEmpty` reports the missing property by name instead of
        // this constructor failing with a bare NullPointerException.
        allowedIssuers = allowedIssuers == null ? null : List.copyOf(allowedIssuers);
        allowedOrigins = allowedOrigins == null ? null : List.copyOf(allowedOrigins);
    }
}
