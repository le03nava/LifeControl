package com.lifecontrol.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Configuración para aceptar tokens JWT del mismo Keycloak
 * aunque el issuer en el token sea diferente.
 *
 * La firma se valida contra las JWK del Keycloak interno y el issuer contra la
 * allowlist configurada ({@code lifecontrol.gateway.allowed-issuers}).
 */
@Configuration
public class MultiIssuerSecurityConfig {

    private final GatewayProperties props;

    public MultiIssuerSecurityConfig(GatewayProperties props) {
        this.props = props;
    }

    @Bean
    public JwtDecoder keycloakJwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
            .withJwkSetUri(props.keycloakInternalUri() + "/protocol/openid-connect/certs")
            .build();

        // createDefault() aporta la validación de exp/nbf (con 60s de leeway).
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(),
            new JwtIssuerAllowlistValidator(props.allowedIssuers()));
        decoder.setJwtValidator(validator);

        return decoder;
    }
}
