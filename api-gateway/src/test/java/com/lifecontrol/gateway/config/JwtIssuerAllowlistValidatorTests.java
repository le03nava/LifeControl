package com.lifecontrol.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtIssuerAllowlistValidatorTests {

  private static final String ALLOWED_ISSUER = "http://localhost:8181/realms/life-control-realm";

  private final JwtIssuerAllowlistValidator validator = new JwtIssuerAllowlistValidator(
      List.of(ALLOWED_ISSUER, "http://lifecontrol-dev-keycloak:8080/realms/life-control-realm"));

  @Test
  void acceptsIssuerInAllowlist() {
    OAuth2TokenValidatorResult result = validator.validate(jwtWithIssuer(ALLOWED_ISSUER));

    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  void acceptsIssuerWithTrailingSlash() {
    OAuth2TokenValidatorResult result = validator.validate(jwtWithIssuer(ALLOWED_ISSUER + "/"));

    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  void rejectsIssuerOutsideAllowlist() {
    OAuth2TokenValidatorResult result = validator.validate(jwtWithIssuer("http://evil.example.com/realms/other"));

    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).extracting(OAuth2Error::getErrorCode)
        .containsExactly("invalid_issuer");
  }

  @Test
  void rejectsMissingIssuer() {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("sub", "user").build();

    assertThat(validator.validate(jwt).hasErrors()).isTrue();
  }

  private Jwt jwtWithIssuer(String issuer) {
    return Jwt.withTokenValue("token").header("alg", "none").claim("iss", issuer).build();
  }
}
