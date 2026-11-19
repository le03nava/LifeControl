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
 * <p>
 * <b>{@code X-Forwarded-For} is walked from the right, not read from the left.</b> A proxy appends
 * the address it saw to whatever the caller already sent, so the header is
 * {@code <caller-written prefix>, …, <the client the last proxy saw>}. The leftmost hop is therefore
 * the caller's own bytes: anyone can send {@code X-Forwarded-For: 127.0.0.1} and be read as loopback.
 * This resolver starts at the rightmost hop and moves leftwards, skipping the hops that are themselves
 * configured trusted proxies; the first hop that is not a trusted proxy is the client, because a
 * trusted proxy wrote everything to the right of it. When every hop is a trusted proxy, the leftmost
 * hop is what the outermost trusted proxy saw and is returned.
 * <p>
 * <b>A hop that is not an IP literal fails closed to the socket peer.</b> Handing a caller-written
 * string to {@code InetAddress.getByName} would resolve it through DNS, so a header could name a host
 * and be read as whatever address the resolver answers. The check is
 * {@link CidrMatcher#looksLikeIp(String)}, the same one the matcher applies.
 */
public final class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String REAL_IP = "X-Real-IP";

    private ClientIpResolver() {}

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

        var forwardedFor = request.getHeader(FORWARDED_FOR);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            var hops = forwardedFor.split(",");
            for (var i = hops.length - 1; i >= 0; i--) {
                var hop = hops[i].trim();
                if (hop.isEmpty()) {
                    continue;
                }
                if (!CidrMatcher.looksLikeIp(hop)) {
                    // A name is not an address: fail closed rather than resolve it.
                    return remoteAddr;
                }
                if (!isTrustedProxy(hop, trustedProxies)) {
                    return hop;
                }
            }
            // Every hop was itself a trusted proxy, so the outermost one's leftmost hop is the client.
            for (var hop : hops) {
                var trimmed = hop.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }

        var realIp = request.getHeader(REAL_IP);
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
        return trustedProxies.stream().anyMatch(rule -> CidrMatcher.matches(ip, rule));
    }
}
