package com.lifecontrol.api.config.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "keycloak")
public record KeycloakJwtProperties(String uri, String issuer, String jwkSetUri, List<String> allowedIssuers) {

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
    }
}
