package com.lifecontrol.api.config.filter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link ContentCachingFilter} at {@link Ordered#HIGHEST_PRECEDENCE} + 10,
 * right after the {@code RateLimitFilter} (which runs at {@code HIGHEST_PRECEDENCE}),
 * so the request body is cached before any controller or aspect reads it.
 */
@Configuration
public class ContentCachingConfig {

    @Bean
    public FilterRegistrationBean<ContentCachingFilter> contentCachingFilterRegistration(
            ContentCachingFilter filter) {

        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
