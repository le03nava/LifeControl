package com.lifecontrol.api.config.logbook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Logbook;

import java.util.List;

/**
 * Configures the Zalando Logbook HTTP request/response logging with
 * sensitive data sanitization.
 * <p>
 * Enabled by default. Set {@code logbook.enabled=false} to disable.
 * <p>
 * Applies:
 * <ul>
 *   <li>Header filtering — redacts Authorization, cookies, API keys</li>
 *   <li>Body filtering — redacts password, secret, token field values in JSON</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "logbook.enabled", havingValue = "true", matchIfMissing = true)
public class LogbookConfig {

    private static final Logger log = LoggerFactory.getLogger(LogbookConfig.class);

    @Bean
    public Logbook logbook(SensitiveDataSanitizer sanitizer) {
        log.debug("Initializing Logbook with sensitive data sanitization");
        return Logbook.builder()
                .headerFilters(List.of(
                        sanitizer.authorizationHeaderFilter(),
                        sanitizer.sensitiveHeadersFilter()))
                .bodyFilter(sanitizer.bodyFilter())
                .requestFilter(sanitizer.requestFilter())
                .responseFilter(sanitizer.responseFilter())
                .build();
    }
}
