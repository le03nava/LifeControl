package com.lifecontrol.api.config.logbook;

import org.springframework.stereotype.Component;
import org.zalando.logbook.BodyFilter;
import org.zalando.logbook.HeaderFilter;
import org.zalando.logbook.RequestFilter;
import org.zalando.logbook.ResponseFilter;
import org.zalando.logbook.core.HeaderFilters;
import org.zalando.logbook.json.JsonBodyFilters;

import java.util.Set;

/**
 * Provides reusable sanitization strategies for Logbook.
 * <p>
 * Defines which headers and body fields to redact from HTTP request/response logs
 * to prevent exposure of Personally Identifiable Information (PII), credentials,
 * and JWT tokens.
 */
@Component
public class SensitiveDataSanitizer {

    /**
     * Header names (case-insensitive) whose values MUST be fully redacted.
     */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "cookie",
            "set-cookie",
            "x-csrf-token",
            "x-xsrf-token",
            "x-api-key"
    );

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
            "private_key"
    );

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
        return HeaderFilters.replaceHeaders(
                name -> SENSITIVE_HEADERS.contains(name.toLowerCase()),
                "[REDACTED]");
    }

    /**
     * Returns a {@link BodyFilter} that redacts the values of known sensitive JSON
     * string properties. Non-sensitive fields and non-JSON content pass through unchanged.
     */
    public BodyFilter bodyFilter() {
        return JsonBodyFilters.replaceJsonStringProperty(
                name -> SENSITIVE_BODY_FIELDS.contains(name.toLowerCase()),
                "[REDACTED]");
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
