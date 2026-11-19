package com.lifecontrol.api.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The other half of S5: {@code security.docs-public=true} is what puts the docs back in
 * {@code permitAll}, mirroring the gateway's idiom. Same real chain as
 * {@link SecurityConfigAccessTest}, with only that one property changed.
 */
@WebMvcTest(controllers = SecurityConfigAccessTest.ProbeController.class, properties = "security.docs-public=true")
@Import({SecurityConfig.class, SecurityConfigAccessTest.ProbeConfig.class})
@DisplayName("SecurityConfig — docs are public only when the property says so")
class SecurityConfigDocsPublicTest {

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

    @Test
    @DisplayName("/v3/api-docs answers when security.docs-public=true")
    void apiDocs_arePublicWhenEnabled() throws Exception {
        var response = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo(SecurityConfigAccessTest.HANDLER_REACHED);
    }

    @Test
    @DisplayName("/swagger-ui.html answers when security.docs-public=true")
    void swaggerUi_arePublicWhenEnabled() throws Exception {
        var response = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo(SecurityConfigAccessTest.HANDLER_REACHED);
    }

    @Test
    @DisplayName("enabling the docs does not open the rest of the tree")
    void otherPaths_stayClosed() throws Exception {
        var response = mockMvc.perform(get("/internal/probe")).andReturn().getResponse();

        assertThat(response.getStatus()).isIn(401, 403);
        assertThat(response.getContentAsString()).doesNotContain(SecurityConfigAccessTest.HANDLER_REACHED);
    }
}
