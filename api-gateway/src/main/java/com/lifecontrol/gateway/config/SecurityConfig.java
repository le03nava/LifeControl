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

  private static final List<String> ALWAYS_PUBLIC_URLS = List.of(
      "/actuator/health", "/actuator/prometheus");

  private static final List<String> DOC_URLS = List.of(
      "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**",
      "/api-docs/**", "/aggregate/**");

  private final GatewayProperties props;
  private final boolean docsPublic;
  private final boolean requireHttps;

  public SecurityConfig(
      GatewayProperties props,
      @Value("${security.docs-public:false}") boolean docsPublic,
      @Value("${security.require-https:false}") boolean requireHttps) {
    this.props = props;
    this.docsPublic = docsPublic;
    this.requireHttps = requireHttps;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity,
      JwtDecoder keycloakJwtDecoder) throws Exception {
    List<String> publicUrls = new ArrayList<>(ALWAYS_PUBLIC_URLS);
    if (docsPublic) {
      publicUrls.addAll(DOC_URLS);
    }

    httpSecurity.authorizeHttpRequests(authorize -> authorize
        .requestMatchers(publicUrls.toArray(String[]::new)).permitAll()
        .anyRequest().authenticated())
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.decoder(keycloakJwtDecoder)))
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
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE",
        "OPTIONS", "HEAD"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(false);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

}
