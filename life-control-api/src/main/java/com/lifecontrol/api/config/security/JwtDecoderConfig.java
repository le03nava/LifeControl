package com.lifecontrol.api.config.security;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Configuration for JWT decoding.
 * Uses Keycloak as the OAuth2 provider with JWK Set URI for signature validation.
 */
@Configuration
public class JwtDecoderConfig {

    @Value("${keycloak.uri:http://lifecontrol-dev-keycloak:8080/realms/life-control-realm}")
    private String keycloakUri;

    @Bean
    public JwtDecoder keycloakJwtDecoder() {
        var jwkSetUri = keycloakUri + "/protocol/openid-connect/certs";
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        // Only validate timestamps, not issuer (Keycloak may use different issuer URIs per environment)
        var timestampValidator = new JwtTimestampValidator(Duration.ofSeconds(60));
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(timestampValidator);
        decoder.setJwtValidator(validator);

        return decoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        
        // Keycloak puts roles in "realm_access.roles", not in "scope"
        grantedAuthoritiesConverter.setAuthoritiesClaimName("realm_access");
        // Map as "ROLE_admin", "ROLE_user"
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }
}