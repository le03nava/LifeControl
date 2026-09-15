package com.lifecontrol.api.config.security;

import com.lifecontrol.api.common.net.CidrMatcher;
import com.lifecontrol.api.common.net.ClientIpResolver;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Security configuration for the REST API.
 * - Requires JWT authentication for all /api/** endpoints
 * - Allows public access to swagger and actuator health endpoints
 * - Restricts other actuator endpoints (e.g. /actuator/prometheus) to internal
 *   client addresses configured via {@code app.security.actuator.allowed-cidrs}
 * - Disables CSRF (appropriate for REST APIs)
 * - CORS is handled by the API Gateway, not here
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityAccessProperties.class)
public class SecurityConfig {

    private static final String[] PUBLIC_URLS = {
        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
        "/swagger-resources/**", "/api-docs/**", "/aggregate/**"
    };

    private final SecurityAccessProperties accessProperties;

    public SecurityConfig(SecurityAccessProperties accessProperties) {
        this.accessProperties = accessProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtDecoder keycloakJwtDecoder, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {

        return http.authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_URLS)
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/actuator/**")
                        .access(this::isInternalClient)
                        .requestMatchers("/api/users-admin/**")
                        .hasAuthority("ROLE_admin")
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                        jwt -> jwt.decoder(keycloakJwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    /**
     * Allows actuator requests only when the resolved client address belongs to
     * one of the configured internal CIDRs. The client IP is resolved through
     * {@link ClientIpResolver}, so forwarded headers are only trusted from proxies.
     */
    private AuthorizationDecision isInternalClient(
            Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        var clientIp = ClientIpResolver.resolve(context.getRequest(), accessProperties.getTrustedProxies());
        var allowed = clientIp != null
                && accessProperties.getActuatorAllowedCidrs().stream()
                        .anyMatch(cidr -> CidrMatcher.matches(clientIp, cidr));
        return new AuthorizationDecision(allowed);
    }
}
