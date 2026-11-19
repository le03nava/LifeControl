package com.lifecontrol.api.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The first test on the tree that exercises the real {@link SecurityConfig#securityFilterChain} —
 * every other web test in this module installs its own {@code TestSecurityConfig} and proves nothing
 * about this class.
 *
 * <p>A controller is mapped here on purpose: a refusal that answers 401/403 only because no handler
 * exists would pass for the wrong reason, so the reachable handler is what makes the assertion mean
 * "the chain stopped it" rather than "there was nothing to reach".</p>
 *
 * <p>Runs under the {@code staging} profile, which is the profile S5 names: the docs are not public
 * unless a property says so.</p>
 */
@WebMvcTest(controllers = SecurityConfigAccessTest.ProbeController.class)
@Import({SecurityConfig.class, SecurityConfigAccessTest.ProbeConfig.class})
@ActiveProfiles("staging")
@DisplayName("SecurityConfig — the real filter chain ends closed")
class SecurityConfigAccessTest {

    /** The body the controllers answer with, so a refusal can be told apart from a reachable handler. */
    static final String HANDLER_REACHED = "handler-reached";

    @RestController
    static class ProbeController {

        @GetMapping("/internal/probe")
        ResponseEntity<String> outsideApi() {
            return ResponseEntity.ok(HANDLER_REACHED);
        }

        @GetMapping("/api/probe")
        ResponseEntity<String> underApi() {
            return ResponseEntity.ok(HANDLER_REACHED);
        }

        @GetMapping("/v3/api-docs")
        ResponseEntity<String> apiDocs() {
            return ResponseEntity.ok(HANDLER_REACHED);
        }

        @GetMapping("/swagger-ui.html")
        ResponseEntity<String> swaggerUi() {
            return ResponseEntity.ok(HANDLER_REACHED);
        }
    }

    /**
     * The probe controller is registered as a bean instead of relying on the slice's component scan:
     * a nested test class is not a scan candidate here, and an unmapped path would answer 404, which
     * is a refusal for the wrong reason.
     */
    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder keycloakJwtDecoder;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private CurrentUserContext currentUserContext;

    /** {@link com.lifecontrol.api.config.ratelimit.RateLimitFilter} is a servlet filter, so the slice
     * picks it up; its configuration is not what this test is about. */
    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    private void assertRefused(int status) {
        assertThat(status).as("the request must be refused by the chain").isIn(401, 403);
    }

    @Test
    @DisplayName("S4: a path outside /api/** and /actuator/** is refused and never reaches its handler")
    void pathOutsideApiAndActuator_isRefused() throws Exception {
        var response = mockMvc.perform(get("/internal/probe")).andReturn().getResponse();

        assertRefused(response.getStatus());
        assertThat(response.getContentAsString())
                .as("the chain must not fall through to the controller")
                .doesNotContain(HANDLER_REACHED);
    }

    @Test
    @DisplayName("the tree-wide default is closed: /api/** still requires authentication")
    void apiPath_stillRequiresAuthentication() throws Exception {
        var response = mockMvc.perform(get("/api/probe")).andReturn().getResponse();

        assertRefused(response.getStatus());
        assertThat(response.getContentAsString()).doesNotContain(HANDLER_REACHED);
    }

    @Test
    @DisplayName("/error stays permitted: the ERROR dispatch must not become a 403")
    void errorPath_staysPermitted() throws Exception {
        var response = mockMvc.perform(get("/error")).andReturn().getResponse();

        assertThat(response.getStatus())
                .as("denying /error turns an unauthenticated failure into a 403")
                .isNotIn(401, 403);
    }

    @Test
    @DisplayName("S5: /v3/api-docs is not public under the staging profile")
    void apiDocs_areNotPublicByDefault() throws Exception {
        var response = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse();

        assertRefused(response.getStatus());
        assertThat(response.getContentAsString()).doesNotContain(HANDLER_REACHED);
    }

    @Test
    @DisplayName("S5: /swagger-ui.html is not public under the staging profile")
    void swaggerUi_isNotPublicByDefault() throws Exception {
        var response = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse();

        assertRefused(response.getStatus());
        assertThat(response.getContentAsString()).doesNotContain(HANDLER_REACHED);
    }

    @Test
    @DisplayName("/actuator/health stays public")
    void actuatorHealth_staysPublic() throws Exception {
        var response = mockMvc.perform(get("/actuator/health")).andReturn().getResponse();

        assertThat(response.getStatus())
                .as("the container healthcheck reads this endpoint unauthenticated")
                .isNotIn(401, 403);
    }
}
