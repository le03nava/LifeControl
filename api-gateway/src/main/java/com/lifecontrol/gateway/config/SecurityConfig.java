package com.lifecontrol.gateway.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    /** The two management endpoints whose only readers are the container healthcheck and the scraper. */
    private static final List<String> ALWAYS_PUBLIC_URLS = List.of("/actuator/health", "/actuator/prometheus");

    /**
     * The rest of the management surface. It is not gated on a role: this gateway derives authorities
     * from the token's {@code scope} claim, so a {@code hasAuthority} rule would refuse administrators
     * as well. It is denied, and only {@code security.actuator-public} (dev) opens it.
     */
    private static final String MANAGEMENT_URLS = "/actuator/**";

    private static final List<String> DOC_URLS = List.of(
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/api-docs/**",
            "/aggregate/**");

    private final GatewayProperties props;
    private final boolean docsPublic;
    private final boolean actuatorPublic;
    private final boolean requireHttps;

    public SecurityConfig(
            GatewayProperties props,
            @Value("${security.docs-public:false}") boolean docsPublic,
            @Value("${security.actuator-public:false}") boolean actuatorPublic,
            @Value("${security.require-https:false}") boolean requireHttps) {
        this.props = props;
        this.docsPublic = docsPublic;
        this.actuatorPublic = actuatorPublic;
        this.requireHttps = requireHttps;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity, JwtDecoder keycloakJwtDecoder)
            throws Exception {
        List<String> publicUrls = new ArrayList<>(ALWAYS_PUBLIC_URLS);
        if (docsPublic) {
            publicUrls.addAll(DOC_URLS);
        }

        httpSecurity
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(publicUrls.toArray(String[]::new)).permitAll();
                    // Declared after the two public endpoints, so `health` and `prometheus` keep
                    // answering the healthcheck and the scraper in every environment.
                    if (actuatorPublic) {
                        authorize.requestMatchers(MANAGEMENT_URLS).permitAll();
                    } else {
                        authorize.requestMatchers(MANAGEMENT_URLS).denyAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(keycloakJwtDecoder)))
                // Stateless bearer-token API: there is no browser session or cookie, so CSRF does not apply.
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.httpStrictTransportSecurity(hsts -> {
                    if (requireHttps) {
                        hsts.includeSubDomains(true).maxAgeInSeconds(31536000);
                    } else {
                        hsts.disable();
                    }
                }));

        return httpSecurity.build();
    }

    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(props.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
