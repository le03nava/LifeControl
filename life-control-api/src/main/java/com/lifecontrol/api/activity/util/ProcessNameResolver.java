package com.lifecontrol.api.activity.util;

import java.util.Locale;

/**
 * Derives the activity process name from a controller's package.
 * <p>
 * Expects the format {@code com.lifecontrol.api.<domain>.controller...} and answers the segment
 * right after {@code com.lifecontrol.api.} uppercased — {@code com.lifecontrol.api.hr.controller}
 * &rarr; {@code HR}. The rule lives here, and not inside the aspect, so that the aspect and the
 * coverage guard that asserts every derived name is seeded read the same implementation: a guard
 * that re-implemented the rule could stay green while the aspect derived something else.
 */
public final class ProcessNameResolver {

    private static final String API_PREFIX = "com.lifecontrol.api.";

    private ProcessNameResolver() {
        // utility class
    }

    /**
     * Resolves the process name for a package.
     *
     * @param packageName the controller's package name, may be {@code null}
     * @return the uppercased domain segment, or {@code null} when the package is outside
     *     {@code com.lifecontrol.api} or carries no domain segment
     */
    public static String resolve(String packageName) {
        if (packageName == null || !packageName.startsWith(API_PREFIX)) {
            return null;
        }
        var afterApi = packageName.substring(API_PREFIX.length());
        var dotIndex = afterApi.indexOf('.');
        var domain = (dotIndex > 0) ? afterApi.substring(0, dotIndex) : afterApi;
        return domain.isEmpty() ? null : domain.toUpperCase(Locale.ROOT);
    }
}
