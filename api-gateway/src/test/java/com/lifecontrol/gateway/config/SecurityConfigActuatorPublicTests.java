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
 * The other half of {@link SecurityConfigTests}: with {@code security.actuator-public} on (what dev
 * declares), the same request is no longer denied. The exposure default stays untouched on purpose —
 * {@code /actuator/metrics} is outside it, so the answer moves from 403 to 404 and not to 200.
 */
@SpringBootTest(properties = "security.actuator-public=true")
@AutoConfigureMockMvc
class SecurityConfigActuatorPublicTests {

    private static final String ISSUER = "http://localhost:8181/realms/life-control-realm";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder keycloakJwtDecoder;

    @Test
    void permitsTheRestOfTheActuatorWhenThePropertyIsOnAndTheDefaultExposureHidesIt() throws Exception {
        stubValidRealmToken("no-admin-token", List.of("openid", "profile", "email"));

        // Not 403: the chain permits it. Not 200 either: `health` and `prometheus` are the only
        // endpoints the shipped exposure declares, so the dispatcher answers 404.
        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer no-admin-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void keepsBothReadersPublicWithThePropertyOn() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
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
