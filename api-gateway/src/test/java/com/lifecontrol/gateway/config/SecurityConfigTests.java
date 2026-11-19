package com.lifecontrol.gateway.config;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the real {@link SecurityConfig} chain. The flag is pinned explicitly to {@code false} so
 * the class asserts the shipped staging/prod behaviour no matter which Spring profile the build runs
 * under ({@code application-dev.properties} turns it on).
 */
@SpringBootTest(properties = "security.actuator-public=false")
@AutoConfigureMockMvc
class SecurityConfigTests {

    private static final String ISSUER = "http://localhost:8181/realms/life-control-realm";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder keycloakJwtDecoder;

    @Test
    void rejectsApiRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/countries")).andExpect(status().isUnauthorized());
    }

    @Test
    void keepsPrometheusScrapePublicWithoutToken() throws Exception {
        // The scraper authenticates with nothing: this is the reader the endpoint exists for.
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void deniesActuatorMetricsToAValidNonAdminToken() throws Exception {
        // A realm token that carries no admin role is still a valid token, and it must not read the
        // management surface: the gateway derives authorities from `scope`, so this is a denial for
        // every caller, administrators included, unless `security.actuator-public` is on.
        stubValidRealmToken("no-admin-token", List.of("openid", "profile", "email"));

        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer no-admin-token"))
                .andExpect(status().isForbidden());
    }

    private void stubValidRealmToken(String token, List<String> scopes) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("operator-without-admin-role")
                .claim("scope", scopes)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        when(keycloakJwtDecoder.decode(token)).thenReturn(jwt);
    }
}
