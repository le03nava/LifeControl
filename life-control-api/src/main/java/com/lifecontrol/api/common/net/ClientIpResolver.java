package com.lifecontrol.api.common.net;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * Resolves the real client IP of an HTTP request.
 * <p>
 * Forwarded headers ({@code X-Forwarded-For}, {@code X-Real-IP}) are only honored
 * when the immediate peer (the reverse proxy / gateway) is a trusted proxy. This
 * prevents clients from spoofing their address to bypass rate limits or IP
 * allow-lists. When the peer is not trusted, the raw socket address is used.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    /**
     * Resolves the client IP for the given request.
     *
     * @param request        the incoming request
     * @param trustedProxies addresses (IP or CIDR) whose forwarded headers may be trusted
     * @return the client IP, or {@code null} when the container reports none
     */
    public static String resolve(HttpServletRequest request, List<String> trustedProxies) {
        var remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr, trustedProxies)) {
            return remoteAddr;
        }

        var forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            var firstHop = forwardedFor.split(",")[0].trim();
            if (!firstHop.isEmpty()) {
                return firstHop;
            }
        }

        var realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return remoteAddr;
    }

    /**
     * Returns {@code true} when the given address is a configured trusted proxy.
     */
    public static boolean isTrustedProxy(String ip, List<String> trustedProxies) {
        if (ip == null || trustedProxies == null) {
            return false;
        }
        return trustedProxies.stream()
                .anyMatch(rule -> CidrMatcher.matches(ip, rule));
    }
}
