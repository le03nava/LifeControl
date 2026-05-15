package com.lifecontrol.api.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtDecoderConfig — JwtAuthenticationConverter Tests")
class JwtDecoderConfigTest {

    private final JwtAuthenticationConverter converter;

    JwtDecoderConfigTest() {
        var config = new JwtDecoderConfig();
        // keycloakUri is null in plain instantiation, but decoder creation isn't needed for converter tests
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

    private List<SimpleGrantedAuthority> extractRoles(Jwt jwt) {
        return converter.convert(jwt).getAuthorities().stream()
                .map(a -> (SimpleGrantedAuthority) a)
                .toList();
    }

    // ─── Happy Path ──────────────────────────────────────────

    @Nested
    @DisplayName("JWT with valid realm_access.roles")
    class ValidRealmAccess {

        @Test
        @DisplayName("extracts ROLE_admin and ROLE_user from realm_access.roles")
        void extractsRolesFromRealmAccess() {
            var jwt = jwtWithClaims(Map.of(
                    "realm_access", Map.of("roles", List.of("admin", "user"))
            ));

            var authorities = extractRoles(jwt);

            assertThat(authorities).hasSize(2);
            assertThat(authorities).contains(
                    new SimpleGrantedAuthority("ROLE_admin"),
                    new SimpleGrantedAuthority("ROLE_user")
            );
        }

        @Test
        @DisplayName("extracts single role from realm_access.roles")
        void extractsSingleRole() {
            var jwt = jwtWithClaims(Map.of(
                    "realm_access", Map.of("roles", List.of("admin"))
            ));

            var authorities = extractRoles(jwt);

            assertThat(authorities).hasSize(1);
            assertThat(authorities).contains(new SimpleGrantedAuthority("ROLE_admin"));
        }

        @Test
        @DisplayName("maps custom role names with ROLE_ prefix")
        void mapsCustomRoleNames() {
            var jwt = jwtWithClaims(Map.of(
                    "realm_access", Map.of("roles", List.of("manager", "viewer"))
            ));

            var authorities = extractRoles(jwt);

            assertThat(authorities).hasSize(2);
            assertThat(authorities).contains(
                    new SimpleGrantedAuthority("ROLE_manager"),
                    new SimpleGrantedAuthority("ROLE_viewer")
            );
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
            var jwt = jwtWithClaims(Map.of(
                    "realm_access", Map.of("roles", List.of())
            ));

            var authorities = extractRoles(jwt);

            assertThat(authorities).isEmpty();
        }
    }
}
