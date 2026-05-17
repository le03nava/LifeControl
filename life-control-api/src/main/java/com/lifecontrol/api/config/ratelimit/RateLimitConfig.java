package com.lifecontrol.api.config.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the rate limiting infrastructure:
 * <ul>
 *   <li>Enables {@link RateLimitProperties} binding</li>
 *   <li>Registers {@link RateLimitFilter} at {@link Ordered#HIGHEST_PRECEDENCE}
 *       so rate limits are enforced before authentication</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter rateLimitFilter) {
        var registration = new FilterRegistrationBean<>(rateLimitFilter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/api/users-admin/*");
        return registration;
    }
}
