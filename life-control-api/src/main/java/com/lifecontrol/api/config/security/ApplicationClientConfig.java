package com.lifecontrol.api.config.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables binding of {@link ApplicationClientProperties}.
 *
 * <p>Mirrors {@code config/ratelimit/RateLimitConfig} + {@code RateLimitProperties}: a
 * {@code @Configuration} class placed beside its properties record, registering it with
 * {@code @EnableConfigurationProperties}. The repository registers every properties record this way
 * rather than through component scanning.</p>
 */
@Configuration
@EnableConfigurationProperties(ApplicationClientProperties.class)
public class ApplicationClientConfig {}
