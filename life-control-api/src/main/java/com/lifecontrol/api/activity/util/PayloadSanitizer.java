package com.lifecontrol.api.activity.util;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Simple utility that redacts sensitive JSON string field values before
 * persisting them in the activity log.
 * <p>
 * The set of sensitive field names mirrors
 * {@link com.lifecontrol.api.config.logbook.SensitiveDataSanitizer}'s private
 * constant so that the same fields are protected in both request/response
 * logging (Logbook) and the activity audit trail.
 */
public final class PayloadSanitizer {

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

    private static final Pattern SENSITIVE_FIELD_PATTERN;

    static {
        var fieldPattern = SENSITIVE_BODY_FIELDS.stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        SENSITIVE_FIELD_PATTERN = Pattern.compile(
                "\"(" + fieldPattern + ")\"\\s*:\\s*\"([^\"]*)\"",
                Pattern.CASE_INSENSITIVE
        );
    }

    private PayloadSanitizer() {
        // utility class
    }

    /**
     * Redacts sensitive field values from a JSON string.
     * <p>
     * Each known sensitive field whose value is a JSON string is replaced with
     * {@code "[REDACTED]"}. Non-string values (numbers, booleans, nested
     * objects) are not affected by the current pattern.
     *
     * @param payload the raw JSON payload, may be {@code null} or blank
     * @return the sanitized JSON string, or the original if nothing to redact
     */
    public static String sanitize(String payload) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }
        return SENSITIVE_FIELD_PATTERN.matcher(payload)
                .replaceAll(mr -> "\"" + mr.group(1) + "\": \"[REDACTED]\"");
    }
}
