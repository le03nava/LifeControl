package com.lifecontrol.api.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the <b>application client</b> — the public Keycloak client the SPA
 * authenticates against (the token's {@code azp}).
 *
 * <p>Prefix: {@code keycloak.app}, mirroring {@code keycloak.admin} (the confidential admin client).
 * The two are different clients with different ids and are deliberately kept in separate namespaces.
 * This id is what a caller passes to {@code IdentityProvider.listClientRoles(clientId)} to validate
 * a role name against the real client roles of the application (record T18, E15).</p>
 *
 * <p>Like {@code KeycloakAdminProperties}, the value is supplied by {@code application.properties}
 * ({@code keycloak.app.client-id=${KEYCLOAK_CLIENT_ID:life-control-client}}).</p>
 */
@ConfigurationProperties(prefix = "keycloak.app")
public record ApplicationClientProperties(String clientId) {}
