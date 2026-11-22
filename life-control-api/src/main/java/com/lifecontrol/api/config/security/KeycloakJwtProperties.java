package com.lifecontrol.api.config.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "keycloak")
public record KeycloakJwtProperties(
        String uri,
        String issuer,
        String jwkSetUri,
        List<String> allowedIssuers,
        List<String> allowedAudiences,
        List<String> authorityClientIds) {

    /**
     * The audience both decoders require by default: a token minted for the platform names it, and a
     * token minted for another resource does not. The realm puts it in {@code aud} through the
     * {@code oidc-audience-mapper} that {@code docker/scripts/keycloak-setup.sh} provisions; without
     * that mapper Keycloak emits {@code aud: ["account"]} and every token would be rejected here.
     */
    public static final String DEFAULT_AUDIENCE = "life-control-api";

    public KeycloakJwtProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = uri;
        }
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            jwkSetUri = uri + "/protocol/openid-connect/certs";
        }
        if (allowedIssuers == null || allowedIssuers.isEmpty()) {
            allowedIssuers = List.of(issuer);
        }
        if (allowedAudiences == null || allowedAudiences.isEmpty()) {
            allowedAudiences = List.of(DEFAULT_AUDIENCE);
        }
        // An empty list is not the same as an unset one: it means "no client is allowed to grant client
        // roles", so `authorityClientIds` stays exactly as bound instead of falling back to anything.
        authorityClientIds = authorityClientIds == null ? List.of() : List.copyOf(authorityClientIds);
    }
}
