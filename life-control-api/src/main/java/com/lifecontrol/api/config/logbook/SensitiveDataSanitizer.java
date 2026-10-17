package com.lifecontrol.api.config.logbook;

import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.zalando.logbook.BodyFilter;
import org.zalando.logbook.HeaderFilter;
import org.zalando.logbook.RequestFilter;
import org.zalando.logbook.ResponseFilter;
import org.zalando.logbook.core.HeaderFilters;
import org.zalando.logbook.json.JsonBodyFilters;

/**
 * Provides reusable sanitization strategies for Logbook.
 * <p>
 * Defines which headers and body fields to redact from HTTP request/response logs
 * to prevent exposure of Personally Identifiable Information (PII), credentials,
 * and JWT tokens.
 * <p>
 * The body handling is profile-split. Outside {@code dev} the body is not emitted at
 * all: {@link #bodyFilter()} answers a fixed redaction instead of the body, because
 * the field-level filter below is partial (it only redacts nine JSON string fields,
 * and passes non-JSON content through unchanged). On {@code dev} the previous
 * field-level behaviour is preserved.
 */
@Component
public class SensitiveDataSanitizer {

    /**
     * The exact value substituted for every body outside {@code dev}. Fixed, so no
     * payload content can survive the filter whatever its content type.
     */
    static final String REDACTED_BODY = "[REDACTED]";

    /** Whether this instance runs on the {@code dev} profile, the only one that emits bodies. */
    private final boolean bodyEmitted;

    public SensitiveDataSanitizer(Environment environment) {
        this.bodyEmitted = environment.matchesProfiles("dev");
    }

    /**
     * Header names (case-insensitive) whose values MUST be fully redacted.
     */
    private static final Set<String> SENSITIVE_HEADERS =
            Set.of("cookie", "set-cookie", "x-csrf-token", "x-xsrf-token", "x-api-key");

    /**
     * JSON body field names (case-insensitive) whose string values MUST be redacted.
     */
    private static final Set<String> SENSITIVE_BODY_FIELDS = Set.of(
            "password",
            "secret",
            "token",
            "access_token",
            "refresh_token",
            "client_secret",
            "api_key",
            "secret_key",
            "private_key");

    /**
     * Returns the default built-in {@link HeaderFilter} that redacts the
     * {@code Authorization} header via Logbook's standard rule.
     */
    public HeaderFilter authorizationHeaderFilter() {
        return HeaderFilters.authorization();
    }

    /**
     * Returns a {@link HeaderFilter} that redacts additional sensitive headers
     * (cookies, CSRF tokens, API keys).
     */
    public HeaderFilter sensitiveHeadersFilter() {
        return HeaderFilters.replaceHeaders(name -> SENSITIVE_HEADERS.contains(name.toLowerCase()), "[REDACTED]");
    }

    /**
     * Returns a {@link BodyFilter}. On {@code dev} it redacts the values of known
     * sensitive JSON string properties and lets everything else through, which is the
     * previous behaviour. On every other profile it discards the body entirely and
     * answers {@value #REDACTED_BODY}, so non-JSON bodies, non-string JSON values and
     * unnamed fields cannot reach the log.
     */
    public BodyFilter bodyFilter() {
        if (!bodyEmitted) {
            return (contentType, body) -> REDACTED_BODY;
        }
        return JsonBodyFilters.replaceJsonStringProperty(
                name -> SENSITIVE_BODY_FIELDS.contains(name.toLowerCase()), REDACTED_BODY);
    }

    /**
     * Returns a no-op {@link RequestFilter} — currently all request sanitization
     * is handled via header and body filters.
     */
    public RequestFilter requestFilter() {
        return request -> request;
    }

    /**
     * Returns a no-op {@link ResponseFilter} — currently all response sanitization
     * is handled via header and body filters.
     */
    public ResponseFilter responseFilter() {
        return response -> response;
    }
}
