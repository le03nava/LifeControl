package com.lifecontrol.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Exercises the validator chain the gateway installs on its {@code JwtDecoder}.
 *
 * <p>The chain is asserted directly rather than through {@code SecurityConfigTests}, which stubs the
 * decoder: a stubbed decoder never runs a validator, so the rejection this unit adds would be
 * invisible there. Everything the chain is built from — timestamps, issuer allowlist, audience — is
 * covered here so that removing any one of the three fails a test.
 */
@DisplayName("MultiIssuerSecurityConfig validator chain")
class MultiIssuerSecurityConfigTests {

    private static final String API_URI = "http://lifecontrol-api:8082";
    private static final String KEYCLOAK_REALM_URI = "http://keycloak:8080/realms/life-control-realm";
    private static final String PUBLIC_ISSUER = "http://localhost:8181/realms/life-control-realm";
    private static final String EXPECTED_AUDIENCE = "life-control-api";

    private final MultiIssuerSecurityConfig config = new MultiIssuerSecurityConfig(new GatewayProperties(
            API_URI,
            KEYCLOAK_REALM_URI,
            List.of(PUBLIC_ISSUER, KEYCLOAK_REALM_URI),
            List.of(EXPECTED_AUDIENCE),
            List.of("http://localhost:4200")));

    private Jwt.Builder validToken() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer(KEYCLOAK_REALM_URI)
                .audience(List.of(EXPECTED_AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plus(5, ChronoUnit.MINUTES));
    }

    @Nested
    @DisplayName("Audience")
    class Audience {

        @Test
        @DisplayName("rejects a token that does not name the gateway in aud")
        void rejectsForeignAudience() {
            Jwt jwt = validToken().audience(List.of("account")).build();

            var result = config.jwtValidator().validate(jwt);

            assertThat(result.hasErrors()).isTrue();
            assertThat(result.getErrors())
                    .anySatisfy(error -> assertThat(error.getDescription()).contains("aud"));
        }

        @Test
        @DisplayName("rejects a token whose aud claim is absent")
        void rejectsMissingAudience() {
            Jwt jwt = Jwt.withTokenValue("test-token")
                    .header("alg", "RS256")
                    .issuer(KEYCLOAK_REALM_URI)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
                    .build();

            var result = config.jwtValidator().validate(jwt);

            assertThat(result.hasErrors()).isTrue();
        }

        @Test
        @DisplayName("accepts a token that names the gateway in aud")
        void acceptsExpectedAudience() {
            Jwt jwt = validToken().build();

            var result = config.jwtValidator().validate(jwt);

            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        @DisplayName("rejects a token whose aud is present but empty")
        void rejectsEmptyAudienceArray() {
            Jwt jwt = validToken().claim("aud", List.of()).build();

            assertThat(config.jwtValidator().validate(jwt).hasErrors()).isTrue();
        }

        @Test
        @DisplayName("accepts an aud emitted as a single string, which JWT permits")
        void acceptsSingleStringAudience() {
            Jwt jwt = validToken().claim("aud", EXPECTED_AUDIENCE).build();

            assertThat(config.jwtValidator().validate(jwt).hasErrors()).isFalse();
        }
    }

    @Nested
    @DisplayName("Issuer")
    class Issuer {

        @Test
        @DisplayName("still rejects a token from an issuer outside the allowlist")
        void rejectsForeignIssuer() {
            Jwt jwt = validToken()
                    .issuer("http://evil-keycloak:8080/realms/other")
                    .build();

            var result = config.jwtValidator().validate(jwt);

            assertThat(result.hasErrors()).isTrue();
            assertThat(result.getErrors())
                    .anySatisfy(error -> assertThat(error.getDescription()).contains("issuer"));
        }
    }

    @Nested
    @DisplayName("Timestamps")
    class Timestamps {

        @Test
        @DisplayName("rejects an expired token")
        void rejectsExpiredToken() {
            Instant past = Instant.now().minus(30, ChronoUnit.MINUTES);
            Jwt jwt = validToken()
                    .issuedAt(past)
                    .expiresAt(past.plus(5, ChronoUnit.MINUTES))
                    .build();

            var result = config.jwtValidator().validate(jwt);

            assertThat(result.hasErrors()).isTrue();
        }

        @Test
        @DisplayName("rejects a token that is not valid yet")
        void rejectsNotYetValidToken() {
            Instant future = Instant.now().plus(30, ChronoUnit.MINUTES);
            Jwt jwt = validToken().notBefore(future).build();

            var result = config.jwtValidator().validate(jwt);

            assertThat(result.hasErrors()).isTrue();
        }
    }
}
