package com.lifecontrol.gateway.config;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Configuración para aceptar tokens JWT del mismo Keycloak
 * aunque el issuer en el token sea diferente.
 * 
 * Usa el Keycloak interno para validar la firma.
 */
@Configuration
public class MultiIssuerSecurityConfig {

    // URI del Keycloak interno (alcanzable desde Docker)
    private static final String KEYCLOAK_INTERNAL_URI = "http://lifecontrol-dev-keycloak:8080/realms/life-control-realm";

    @Bean
    public JwtDecoder keycloakJwtDecoder() {
        // Crear decoder que NO valida el issuer
        // Solo valida la firma usando las JWK del Keycloak interno
        NimbusJwtDecoder decoder = NimbusJwtDecoder
            .withJwkSetUri(KEYCLOAK_INTERNAL_URI + "/protocol/openid-connect/certs")
            .build();
        
        // Configurar validadores que NO incluyen el issuer
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ofSeconds(60))
        ));
        
        return decoder;
    }
}
