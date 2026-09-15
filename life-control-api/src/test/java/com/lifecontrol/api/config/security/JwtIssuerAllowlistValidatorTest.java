package com.lifecontrol.api.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("JwtIssuerAllowlistValidator Tests")
class JwtIssuerAllowlistValidatorTest {

    private static final String PUBLIC_ISSUER = "http://localhost:8181/realms/life-control-realm";
    private static final String INTERNAL_ISSUER = "http://keycloak:8080/realms/life-control-realm";

    private final JwtIssuerAllowlistValidator validator =
            new JwtIssuerAllowlistValidator(List.of(PUBLIC_ISSUER, INTERNAL_ISSUER));

    @Test
    @DisplayName("accepts issuer in allowlist")
    void acceptsIssuerInAllowlist() {
        assertThat(validator.validate(jwtWithIssuer(PUBLIC_ISSUER)).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("accepts issuer with trailing slash")
    void acceptsIssuerWithTrailingSlash() {
        assertThat(validator.validate(jwtWithIssuer(PUBLIC_ISSUER + "/")).hasErrors())
                .isFalse();
    }

    @Test
    @DisplayName("rejects issuer outside allowlist")
    void rejectsIssuerOutsideAllowlist() {
        OAuth2TokenValidatorResult result = validator.validate(jwtWithIssuer("http://evil.example.com/realms/other"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting(OAuth2Error::getErrorCode).containsExactly("invalid_issuer");
    }

    @Test
    @DisplayName("rejects missing issuer")
    void rejectsMissingIssuer() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "user")
                .build();

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    private Jwt jwtWithIssuer(String issuer) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("iss", issuer)
                .build();
    }
}
