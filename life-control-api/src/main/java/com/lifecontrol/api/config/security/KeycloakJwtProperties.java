package com.lifecontrol.api.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "keycloak")
public record KeycloakJwtProperties(
    String uri,
    String issuer,
    String jwkSetUri
) {

    public KeycloakJwtProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = uri;
        }
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            jwkSetUri = uri + "/protocol/openid-connect/certs";
        }
    }
}