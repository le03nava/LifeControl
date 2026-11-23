package com.lifecontrol.api.config.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Configuration for JWT decoding.
 * Uses Keycloak as the OAuth2 provider with JWK Set URI for signature validation
 * and enforces the expected issuer to reject tokens minted by other providers/realms.
 */
@Configuration
@EnableConfigurationProperties(KeycloakJwtProperties.class)
public class JwtDecoderConfig {

    private final KeycloakJwtProperties keycloakJwtProperties;

    public JwtDecoderConfig(KeycloakJwtProperties keycloakJwtProperties) {
        this.keycloakJwtProperties = keycloakJwtProperties;
    }

    @Bean
    public JwtDecoder keycloakJwtDecoder() {
        var decoder = NimbusJwtDecoder.withJwkSetUri(keycloakJwtProperties.jwkSetUri())
                .build();
        decoder.setJwtValidator(jwtValidator());

        return decoder;
    }

    OAuth2TokenValidator<Jwt> jwtValidator() {
        // createDefault() aporta la validación de exp/nbf (con 60s de leeway);
        // el issuer se valida contra la allowlist (issuer público del navegador
        // e interno de la red Docker), mientras la firma se valida contra el JWK interno.
        // La audiencia se valida contra la lista configurada: sin ella cualquier token del realm,
        // emitido por cualquier cliente y para cualquier otro recurso, pasaba.
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtIssuerAllowlistValidator(keycloakJwtProperties.allowedIssuers()),
                new JwtAudienceValidator(keycloakJwtProperties.allowedAudiences()));
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities = new ArrayList<GrantedAuthority>();

            // 1) Realm roles from realm_access.roles
            Map<String, Object> realmAccess;
            try {
                realmAccess = jwt.getClaimAsMap("realm_access");
            } catch (IllegalArgumentException e) {
                realmAccess = null;
            }
            if (realmAccess != null) {
                @SuppressWarnings("unchecked")
                var realmRoles = (List<String>) realmAccess.get("roles");
                if (realmRoles != null) {
                    realmRoles.stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .forEach(authorities::add);
                }
            }

            // 2) Client roles, read only from the clients that are meant to grant them.
            // Keying this on the token's own `azp` handed authority to whichever client minted it: a
            // role named `lc-admin` in any other client of the realm became API authority. The
            // configured list is what makes the granting client a property of this service instead of
            // of the token.
            Map<String, Object> resourceAccess;
            try {
                resourceAccess = jwt.getClaimAsMap("resource_access");
            } catch (IllegalArgumentException e) {
                resourceAccess = null;
            }
            if (resourceAccess != null) {
                for (var authorityClientId : keycloakJwtProperties.authorityClientIds()) {
                    @SuppressWarnings("unchecked")
                    var clientAccess = (Map<String, Object>) resourceAccess.get(authorityClientId);
                    if (clientAccess == null) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    var clientRoles = (List<String>) clientAccess.get("roles");
                    if (clientRoles != null) {
                        clientRoles.stream()
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                .forEach(authorities::add);
                    }
                }
            }

            return authorities;
        });
        return converter;
    }
}
