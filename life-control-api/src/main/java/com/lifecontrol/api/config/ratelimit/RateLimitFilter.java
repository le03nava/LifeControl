package com.lifecontrol.api.config.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Once-per-request filter that enforces rate limits on {@code /api/users-admin/**} endpoints.
 * <p>
 * Uses Bucket4j token-bucket algorithm with in-memory storage. Each endpoint has an
 * independent bucket configured via {@link RateLimitProperties}. Internal IP whitelist
 * bypasses rate limiting entirely.
 * <p>
 * Adds {@code X-RateLimit-*} headers to every response from the matched endpoints.
 * Returns HTTP 429 with {@code Retry-After} when the limit is exceeded.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String HEADER_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RESET = "X-RateLimit-Reset";
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    private static final String APPLICATION_JSON = "application/json";

    private final RateLimitProperties properties;

    /**
     * Holds a Bucket4j bucket along with its configured maximum request count
     * so we can report it in the {@code X-RateLimit-Limit} header.
     */
    private final Map<String, RateLimitedEndpoint> endpoints = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        var path = request.getRequestURI();
        return !path.startsWith("/api/users-admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (isWhitelisted(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        var endpointKey = resolveEndpointKey(request);
        var endpoint = resolveEndpoint(endpointKey);

        if (endpoint == null) {
            // No rate limit configured for this path — allow through
            filterChain.doFilter(request, response);
            return;
        }

        var probe = endpoint.bucket().tryConsumeAndReturnRemaining(1);
        var nowEpochSecond = Instant.now().getEpochSecond();

        response.setHeader(HEADER_LIMIT, String.valueOf(endpoint.maxRequests()));
        response.setHeader(HEADER_REMAINING, String.valueOf(probe.getRemainingTokens()));
        response.setHeader(HEADER_RESET, String.valueOf(nowEpochSecond + probe.getNanosToWaitForReset() / 1_000_000_000L));

        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
        } else {
            var retryAfterSeconds = probe.getNanosToWaitForRefill() / 1_000_000_000L;
            if (retryAfterSeconds < 1) {
                // Ensure at least 1 second retry-after for UX
                retryAfterSeconds = 1;
            }
            response.setHeader(HEADER_RETRY_AFTER, String.valueOf(retryAfterSeconds));
            response.setStatus(429);
            response.setContentType(APPLICATION_JSON);
            response.getWriter().write(buildRateLimitExceededBody(retryAfterSeconds));
            log.warn("Rate limit exceeded for endpoint [{}] from IP [{}]",
                    endpointKey, getClientIp(request));
        }
    }

    /**
     * Returns {@code true} if the request originates from a whitelisted internal IP.
     */
    private boolean isWhitelisted(HttpServletRequest request) {
        var clientIp = getClientIp(request);
        if (clientIp == null) {
            return false;
        }
        for (var whitelisted : properties.getInternalIpWhitelist()) {
            if (whitelisted.contains("/")) {
                if (matchesCidr(clientIp, whitelisted)) {
                    return true;
                }
            } else if (whitelisted.equals(clientIp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the request to a configured endpoint key by longest-prefix matching.
     */
    private String resolveEndpointKey(HttpServletRequest request) {
        var path = request.getRequestURI();
        String bestMatch = path;
        int bestLength = 0;
        for (var key : properties.getEndpoints().keySet()) {
            if (path.startsWith(key) && key.length() > bestLength) {
                bestMatch = key;
                bestLength = key.length();
            }
        }
        return bestMatch;
    }

    /**
     * Returns the {@link RateLimitedEndpoint} for the given key, creating it
     * lazily from configuration if necessary.
     */
    private RateLimitedEndpoint resolveEndpoint(String endpointKey) {
        return endpoints.computeIfAbsent(endpointKey, key -> {
            var limit = properties.getEndpoints().get(key);
            if (limit == null) {
                return null;
            }
            var bandwidth = Bandwidth.builder()
                    .capacity(limit.maxRequests())
                    .refillIntervally(limit.maxRequests(), limit.duration())
                    .build();
            var bucket = Bucket.builder()
                    .addLimit(bandwidth)
                    .build();
            return new RateLimitedEndpoint(bucket, limit.maxRequests());
        });
    }

    private String getClientIp(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean matchesCidr(String ip, String cidr) {
        try {
            var addr = InetAddress.getByName(ip);
            var addrBytes = addr.getAddress();

            var parts = cidr.split("/");
            var cidrAddr = InetAddress.getByName(parts[0]);
            var cidrBytes = cidrAddr.getAddress();
            var prefixLen = Integer.parseInt(parts[1]);

            if (addrBytes.length != cidrBytes.length) {
                return false;
            }

            var fullBytes = prefixLen / 8;
            var remainingBits = prefixLen % 8;

            for (var i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != cidrBytes[i]) {
                    return false;
                }
            }

            if (remainingBits > 0) {
                var mask = (byte) (0xFF << (8 - remainingBits));
                if ((addrBytes[fullBytes] & mask) != (cidrBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (UnknownHostException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            log.warn("Failed to parse CIDR rule [{}] for IP [{}]", cidr, ip, e);
            return false;
        }
    }

    private String buildRateLimitExceededBody(long retryAfterSeconds) {
        return "{\"status\":429,\"error\":\"Too Many Requests\","
                + "\"message\":\"Rate limit exceeded. Please retry in " + retryAfterSeconds + " seconds.\"}";
    }

    /**
     * Associates a Bucket4j {@link Bucket} with its configured max-request limit
     * for use in response headers.
     */
    private record RateLimitedEndpoint(Bucket bucket, int maxRequests) {}
}
