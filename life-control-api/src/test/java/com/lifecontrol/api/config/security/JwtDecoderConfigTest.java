package com.lifecontrol.api.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

@DisplayName("JwtDecoderConfig Tests")
class JwtDecoderConfigTest {

    private static final String EXPECTED_ISSUER = "http://lifecontrol-dev-keycloak:8080/realms/life-control-realm";
    private static final String PUBLIC_ISSUER = "http://localhost:8181/realms/life-control-realm";
    private static final String JWK_SET_URI = EXPECTED_ISSUER + "/protocol/openid-connect/certs";
    private static final String EXPECTED_AUDIENCE = "life-control-api";
    private static final String AUTHORITY_CLIENT = "life-control-client";
    private static final String FOREIGN_CLIENT = "another-client-of-the-same-realm";

    private final JwtDecoderConfig config;
    private final JwtAuthenticationConverter converter;

    JwtDecoderConfigTest() {
        this.config = new JwtDecoderConfig(new KeycloakJwtProperties(
                EXPECTED_ISSUER,
                EXPECTED_ISSUER,
                JWK_SET_URI,
                List.of(PUBLIC_ISSUER, EXPECTED_ISSUER),
                List.of(EXPECTED_AUDIENCE),
                List.of(AUTHORITY_CLIENT)));
        this.converter = config.jwtAuthenticationConverter();
    }

    /**
     * Helper to create a Jwt with custom claims map.
     */
    private Jwt jwtWithClaims(Map<String, Object> claims) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .build();
    }

    /**
     * Returns the role authorities the converter derives from the token claims.
     * <p>
     * Spring Security 7 appends {@link FactorGrantedAuthority#BEARER_AUTHORITY}
     * ({@code FACTOR_BEARER}) to every JWT authentication, so the role assertions below
     * filter the authorities down to the {@link SimpleGrantedAuthority} instances the
     * converter itself produces; {@link #addsBearerFactorAuthority()} pins that factor
     * authority separately instead of silently dropping it from the contract.
     */
    private List<SimpleGrantedAuthority> extractRoles(Jwt jwt) {
        return converter.convert(jwt).getAuthorities().stream()
                .filter(SimpleGrantedAuthority.class::isInstance)
                .map(SimpleGrantedAuthority.class::cast)
                .toList();
    }

    @Test
    @DisplayName("adds the bearer factor authority that Spring Security 7 appends")
    void addsBearerFactorAuthority() {
        var jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of("admin"))));

        assertThat(converter.convert(jwt).getAuthorities())
                .anySatisfy(authority ->
                        assertThat(authority.getAuthority()).isEqualTo(FactorGrantedAuthority.BEARER_AUTHORITY));
    }

    // ─── Happy Path ──────────────────────────────────────────

    @Nested
    @DisplayName("JWT with valid realm_access.roles")
    class ValidRealmAccess {

        @Test
        @DisplayName("extracts ROLE_admin and ROLE_user from realm_access.roles")
        void extractsRolesFromRealmAccess() {
            var jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of("admin", "user"))));

            var authorities = extractRoles(jwt);

            assertThat(authorities).hasSize(2);
            assertThat(authorities)
                    .contains(new SimpleGrantedAuthority("ROLE_admin"), new SimpleGrantedAuthority("ROLE_user"));
        }

        @Test
        @DisplayName("extracts single role from realm_access.roles")
        void extractsSingleRole() {
            var jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of("admin"))));

            var authorities = extractRoles(jwt);

            assertThat(authorities).hasSize(1);
            assertThat(authorities).contains(new SimpleGrantedAuthority("ROLE_admin"));
        }

        @Test
        @DisplayName("maps custom role names with ROLE_ prefix")
        void mapsCustomRoleNames() {
            var jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of("manager", "viewer"))));

            var authorities = extractRoles(jwt);

            assertThat(authorities).hasSize(2);
            assertThat(authorities)
                    .contains(new SimpleGrantedAuthority("ROLE_manager"), new SimpleGrantedAuthority("ROLE_viewer"));
        }
    }

    // ─── Missing/Malformed Claims ────────────────────────────

    @Nested
    @DisplayName("JWT without realm_access claim")
    class MissingRealmAccess {

        @Test
        @DisplayName("returns empty authorities when realm_access claim is absent")
        void missingRealmAccess_returnsEmpty() {
            var jwt = jwtWithClaims(Map.of("sub", "user-1"));

            var authorities = extractRoles(jwt);

            assertThat(authorities).isEmpty();
        }

        @Test
        @DisplayName("returns empty authorities when realm_access is not a map")
        void malformedRealmAccess_returnsEmpty() {
            var jwt = jwtWithClaims(Map.of("realm_access", "not-a-map"));

            var authorities = extractRoles(jwt);

            assertThat(authorities).isEmpty();
        }

        @Test
        @DisplayName("returns empty authorities when realm_access has no roles key")
        void realmAccessWithoutRoles_returnsEmpty() {
            var jwt = jwtWithClaims(Map.of(
                    "realm_access", Map.of() // empty map, no "roles" key
                    ));

            var authorities = extractRoles(jwt);

            assertThat(authorities).isEmpty();
        }

        @Test
        @DisplayName("returns empty authorities when roles array is empty")
        void emptyRolesArray_returnsEmpty() {
            var jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of())));

            var authorities = extractRoles(jwt);

            assertThat(authorities).isEmpty();
        }
    }

    // ─── Issuer Validation ──────────────────────────────────

    @Nested
    @DisplayName("Issuer validation")
    class IssuerValidation {

        private Jwt jwtWithExpiringClaims(String issuer) {
            var now = Instant.now();
            return Jwt.withTokenValue("test-token")
                    .header("alg", "RS256")
                    .issuer(issuer)
                    // The audience is present in every token here on purpose: these cases assert the
                    // issuer rejection, so they must not fail for a second, unrelated reason.
                    .audience(List.of(EXPECTED_AUDIENCE))
                    .issuedAt(now)
                    .expiresAt(now.plus(5, ChronoUnit.MINUTES))
                    .build();
        }

        @Test
        @DisplayName("rejects token issued by a different issuer")
        void rejectsTokenFromWrongIssuer() {
            var jwt = jwtWithExpiringClaims("http://evil-keycloak:8080/realms/other-realm");

            var result = config.jwtValidator().validate(jwt);

            assertThat(result.hasErrors()).isTrue();
            assertThat(result.getErrors())
                    .anySatisfy(error -> assertThat(error.getDescription()).contains("issuer"));
        }

        @Test
        @DisplayName("accepts token issued by the expected issuer")
        void acceptsTokenFromExpectedIssuer() {
            var jwt = jwtWithExpiringClaims(EXPECTED_ISSUER);

            var result = config.jwtValidator().validate(jwt);

            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        @DisplayName("accepts token issued by the public browser issuer")
        void acceptsTokenFromPublicIssuer() {
            var jwt = jwtWithExpiringClaims(PUBLIC_ISSUER);

            var result = config.jwtValidator().validate(jwt);

            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        @DisplayName("accepts token whose issuer has a trailing slash")
        void acceptsTokenWithTrailingSlashIssuer() {
            var jwt = jwtWithExpiringClaims(PUBLIC_ISSUER + "/");

            var result = config.jwtValidator().validate(jwt);

            assertThat(result.hasErrors()).isFalse();
        }
    }

    // ─── Timestamp Validation ────────────────────────────────

    @Nested
    @DisplayName("Timestamp validation")
    class TimestampValidation {

        @Test
        @DisplayName("rejects an expired token")
        void rejectsExpiredToken() {
            var past = Instant.now().minus(30, ChronoUnit.MINUTES);
            var jwt = Jwt.withTokenValue("test-token")
                    .header("alg", "RS256")
                    .issuer(EXPECTED_ISSUER)
                    .audience(List.of(EXPECTED_AUDIENCE))
                    .issuedAt(past)
                    .expiresAt(past.plus(5, ChronoUnit.MINUTES))
                    .build();

            assertThat(config.jwtValidator().validate(jwt).hasErrors()).isTrue();
        }

        @Test
        @DisplayName("rejects a token that is not valid yet")
        void rejectsNotYetValidToken() {
            var now = Instant.now();
            var jwt = Jwt.withTokenValue("test-token")
                    .header("alg", "RS256")
                    .issuer(EXPECTED_ISSUER)
                    .audience(List.of(EXPECTED_AUDIENCE))
                    .notBefore(now.plus(30, ChronoUnit.MINUTES))
                    .issuedAt(now)
                    .expiresAt(now.plus(60, ChronoUnit.MINUTES))
                    .build();

            assertThat(config.jwtValidator().validate(jwt).hasErrors()).isTrue();
        }
    }

    // ─── Audience Validation ─────────────────────────────────

    @Nested
    @DisplayName("Audience validation")
    class AudienceValidation {

        private Jwt jwtWithAudience(List<String> audience) {
            var now = Instant.now();
            var builder = Jwt.withTokenValue("test-token")
                    .header("alg", "RS256")
                    .issuer(EXPECTED_ISSUER)
                    .issuedAt(now)
                    .expiresAt(now.plus(5, ChronoUnit.MINUTES));
            if (audience != null) {
                builder.audience(audience);
            }
            return builder.build();
        }

        @Test
        @DisplayName("rejects a token that does not name the API in aud")
        void rejectsTokenWithoutTheExpectedAudience() {
            var jwt = jwtWithAudience(List.of("account"));

            var result = config.jwtValidator().validate(jwt);

            assertThat(result.hasErrors()).isTrue();
            assertThat(result.getErrors())
                    .anySatisfy(error -> assertThat(error.getDescription()).contains("aud"));
        }

        @Test
        @DisplayName("rejects a token whose aud claim is missing, rather than treating it as a pass")
        void rejectsTokenWithMissingAudience() {
            var jwt = jwtWithAudience(null);

            var result = config.jwtValidator().validate(jwt);

            assertThat(result.hasErrors()).isTrue();
        }

        @Test
        @DisplayName("accepts a token that names the API in aud alongside other audiences")
        void acceptsTokenNamingTheExpectedAudience() {
            var jwt = jwtWithAudience(List.of("account", EXPECTED_AUDIENCE));

            var result = config.jwtValidator().validate(jwt);

            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        @DisplayName("rejects a token whose aud is present but empty")
        void rejectsEmptyAudienceArray() {
            // An empty array is a claim that is present and names nobody: the failure has to come from the
            // membership check, not from the null guard.
            var jwt = jwtWithAudience(List.of());

            assertThat(config.jwtValidator().validate(jwt).hasErrors()).isTrue();
        }

        @Test
        @DisplayName("accepts an aud emitted as a single string, which JWT permits")
        void acceptsSingleStringAudience() {
            var now = Instant.now();
            var jwt = Jwt.withTokenValue("test-token")
                    .header("alg", "RS256")
                    .issuer(EXPECTED_ISSUER)
                    .claim("aud", EXPECTED_AUDIENCE)
                    .issuedAt(now)
                    .expiresAt(now.plus(5, ChronoUnit.MINUTES))
                    .build();

            assertThat(config.jwtValidator().validate(jwt).hasErrors()).isFalse();
        }

        @Test
        @DisplayName("rejects an aud that differs only in case")
        void rejectsCaseDifferingAudience() {
            // Audiences are compared by value, not folded: a near miss fails closed instead of matching.
            var jwt = jwtWithAudience(List.of(EXPECTED_AUDIENCE.toUpperCase(java.util.Locale.ROOT)));

            assertThat(config.jwtValidator().validate(jwt).hasErrors()).isTrue();
        }
    }

    // ─── Client role authority ───────────────────────────────

    @Nested
    @DisplayName("Client role authority")
    class ClientRoleAuthority {

        private Jwt jwtWithResourceAccess(String azp, String clientWithRoles) {
            return jwtWithClaims(Map.of(
                    "azp", azp, "resource_access", Map.of(clientWithRoles, Map.of("roles", List.of("lc-admin")))));
        }

        @Test
        @DisplayName("grants client roles carried by the client that is meant to grant them")
        void grantsRolesFromTheAuthorityClient() {
            var jwt = jwtWithResourceAccess(AUTHORITY_CLIENT, AUTHORITY_CLIENT);

            assertThat(extractRoles(jwt)).contains(new SimpleGrantedAuthority("ROLE_lc-admin"));
        }

        @Test
        @DisplayName("ignores a colliding role carried by another client of the same realm")
        void ignoresCollidingRoleFromAnotherClient() {
            var jwt = jwtWithResourceAccess(FOREIGN_CLIENT, FOREIGN_CLIENT);

            assertThat(extractRoles(jwt)).doesNotContain(new SimpleGrantedAuthority("ROLE_lc-admin"));
        }

        @Test
        @DisplayName("ignores a colliding role even when the foreign client also carries the expected one")
        void ignoresForeignRolesWhenBothArePresent() {
            var jwt = jwtWithClaims(Map.of(
                    "azp",
                    FOREIGN_CLIENT,
                    "resource_access",
                    Map.of(
                            FOREIGN_CLIENT, Map.of("roles", List.of("lc-admin")),
                            AUTHORITY_CLIENT, Map.of("roles", List.of("lc-sales")))));

            assertThat(extractRoles(jwt))
                    .contains(new SimpleGrantedAuthority("ROLE_lc-sales"))
                    .doesNotContain(new SimpleGrantedAuthority("ROLE_lc-admin"));
        }

        @Test
        @DisplayName("grants no client roles at all when no client is configured to grant them")
        void grantsNoClientRolesWhenTheAuthorityListIsEmpty() {
            // Fail closed: an unset or emptied list must not fall back to reading whoever minted the token.
            var configWithNoAuthorityClient = new JwtDecoderConfig(new KeycloakJwtProperties(
                    EXPECTED_ISSUER,
                    EXPECTED_ISSUER,
                    JWK_SET_URI,
                    List.of(EXPECTED_ISSUER),
                    List.of(EXPECTED_AUDIENCE),
                    List.of()));
            var jwt = jwtWithResourceAccess(AUTHORITY_CLIENT, AUTHORITY_CLIENT);

            var roles = configWithNoAuthorityClient.jwtAuthenticationConverter().convert(jwt).getAuthorities().stream()
                    .filter(SimpleGrantedAuthority.class::isInstance)
                    .map(SimpleGrantedAuthority.class::cast)
                    .toList();

            assertThat(roles).isEmpty();
        }

        @Test
        @DisplayName("survives a resource_access claim that is not a map")
        void ignoresMalformedResourceAccess() {
            var jwt = jwtWithClaims(Map.of("azp", AUTHORITY_CLIENT, "resource_access", "not-a-map"));

            assertThat(extractRoles(jwt)).isEmpty();
        }
    }
}
