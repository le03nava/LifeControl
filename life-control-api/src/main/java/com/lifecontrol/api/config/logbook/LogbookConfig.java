package com.lifecontrol.api.config.logbook;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.Strategy;
import org.zalando.logbook.core.DefaultStrategy;
import org.zalando.logbook.core.QueryFilters;
import org.zalando.logbook.core.StatusAtLeastStrategy;

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
 *   <li>Query filtering — redacts an {@code access_token} parameter</li>
 *   <li>Volume — every exchange on the {@code dev} profile, only refusals on the rest</li>
 * </ul>
 * <p>
 * None of this reaches the log on its own: Logbook's default writer emits at TRACE,
 * and {@code logback-spring.xml} is where that logger is enabled.
 */
@Configuration
@ConditionalOnProperty(name = "logbook.enabled", havingValue = "true", matchIfMissing = true)
public class LogbookConfig {

    private static final Logger log = LoggerFactory.getLogger(LogbookConfig.class);

    /**
     * Status at and above which a non-dev environment logs the exchange. A refusal is
     * the case an operator has to reconstruct afterwards, and it is the only one worth
     * the volume.
     */
    private static final int MINIMUM_LOGGED_STATUS = 400;

    /**
     * Decides what Logbook emits. On {@code dev} the default strategy logs every
     * exchange as it happens; on every other profile {@link StatusAtLeastStrategy}
     * withholds the request half until the response arrives and then emits the request
     * and the response together, but only for a status at or above
     * {@value #MINIMUM_LOGGED_STATUS}.
     * <p>
     * The profile, not a property, is the source of truth. The starter's
     * {@code logbook.strategy} and {@code logbook.write.status-code-based} bind to its
     * own auto-configured {@code Logbook}, and this application defines that bean
     * itself, so those properties never reach this builder.
     */
    @Bean
    public Strategy logbookStrategy(Environment environment) {
        if (environment.matchesProfiles("dev")) {
            return new DefaultStrategy();
        }
        return new StatusAtLeastStrategy(MINIMUM_LOGGED_STATUS);
    }

    @Bean
    public Logbook logbook(SensitiveDataSanitizer sanitizer, Strategy logbookStrategy) {
        log.debug("Initializing Logbook with sensitive data sanitization");
        return Logbook.builder()
                .headerFilters(List.of(sanitizer.authorizationHeaderFilter(), sanitizer.sensitiveHeadersFilter()))
                .bodyFilter(sanitizer.bodyFilter())
                // The starter obfuscates `access_token` by default, but its Logbook bean is
                // not the one in use here, so the same default is applied explicitly.
                .queryFilter(QueryFilters.accessToken())
                .requestFilter(sanitizer.requestFilter())
                .responseFilter(sanitizer.responseFilter())
                .strategy(logbookStrategy)
                .build();
    }
}
