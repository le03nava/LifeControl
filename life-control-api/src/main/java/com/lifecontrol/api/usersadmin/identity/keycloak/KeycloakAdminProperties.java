package com.lifecontrol.api.usersadmin.identity.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "keycloak.admin")
public record KeycloakAdminProperties(
    String serverUrl,
    String realm,
    String clientId,
    String clientSecret
) {}
