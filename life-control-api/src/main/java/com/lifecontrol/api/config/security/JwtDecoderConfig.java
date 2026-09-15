package com.lifecontrol.api.config.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
        return JwtValidators.createDefaultWithIssuer(keycloakJwtProperties.issuer());
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

            // 2) Client roles from resource_access.<azp>.roles
            Map<String, Object> resourceAccess;
            try {
                resourceAccess = jwt.getClaimAsMap("resource_access");
            } catch (IllegalArgumentException e) {
                resourceAccess = null;
            }
            if (resourceAccess != null) {
                var clientName = jwt.getClaimAsString("azp");
                if (clientName != null) {
                    @SuppressWarnings("unchecked")
                    var clientAccess = (Map<String, Object>) resourceAccess.get(clientName);
                    if (clientAccess != null) {
                        @SuppressWarnings("unchecked")
                        var clientRoles = (List<String>) clientAccess.get("roles");
                        if (clientRoles != null) {
                            clientRoles.stream()
                                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                    .forEach(authorities::add);
                        }
                    }
                }
            }

            return authorities;
        });
        return converter;
    }
}
