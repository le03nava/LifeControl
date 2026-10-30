package com.lifecontrol.api.config.security;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.net.CidrMatcher;
import com.lifecontrol.api.common.net.ClientIpResolver;
import com.lifecontrol.api.common.tenant.TenantScopeFilter;
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
import org.springframework.security.web.access.ExceptionTranslationFilter;
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
            HttpSecurity http,
            JwtDecoder keycloakJwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            CurrentUserContext currentUserContext)
            throws Exception {

        // The tenant scope is stated after authentication and before the controller. Its anchor is
        // ExceptionTranslationFilter, placed AFTER it and not before, and that is a measurement: an
        // exception thrown by a filter that sits before ExceptionTranslationFilter propagates outward and
        // never reaches it, so the refusal escaped as a servlet exception instead of the HTTP 403 the rest
        // of the tree answers with. Inside it, the same AccessDeniedException becomes the bodiless 403
        // Spring Security already produces for a filter-level refusal, and the filter still runs long after
        // the resource server validated the JWT. It is built here and not declared as a @Component because
        // Boot registers every Filter bean in the servlet container as well, where it would run a second
        // time with no principal (record L13).
        var tenantScopeFilter = new TenantScopeFilter(currentUserContext);

        return http.addFilterAfter(tenantScopeFilter, ExceptionTranslationFilter.class)
                .authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_URLS)
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
     * <p>
     * Spring Security 7 replaced {@code AuthorizationManager#check(Supplier, T)} with
     * {@code authorize(Supplier<? extends Authentication>, T)}, so this method reference
     * must accept the wildcard supplier. The authentication supplier itself is not consulted.
     */
    private AuthorizationDecision isInternalClient(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        var clientIp = ClientIpResolver.resolve(context.getRequest(), accessProperties.getTrustedProxies());
        var allowed = clientIp != null
                && accessProperties.getActuatorAllowedCidrs().stream()
                        .anyMatch(cidr -> CidrMatcher.matches(clientIp, cidr));
        return new AuthorizationDecision(allowed);
    }
}
