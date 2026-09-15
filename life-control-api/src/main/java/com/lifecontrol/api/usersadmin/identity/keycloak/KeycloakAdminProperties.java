package com.lifecontrol.api.usersadmin.identity.keycloak;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "keycloak.admin")
public record KeycloakAdminProperties(
        String serverUrl,
        String realm,
        String clientId,
        @NotBlank(message = "keycloak.admin.client-secret is required (KEYCLOAK_ADMIN_CLIENT_SECRET)")
                String clientSecret) {}
