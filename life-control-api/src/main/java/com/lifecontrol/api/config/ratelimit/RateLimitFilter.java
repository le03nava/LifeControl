package com.lifecontrol.api.config.ratelimit;

import com.lifecontrol.api.common.net.CidrMatcher;
import com.lifecontrol.api.common.net.ClientIpResolver;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Once-per-request filter that enforces rate limits on {@code /api/users-admin/**} endpoints.
 * <p>
 * Uses Bucket4j token-bucket algorithm with in-memory storage. Each endpoint has an
 * independent bucket configured via {@link RateLimitProperties}. Internal IP whitelist
 * bypasses rate limiting entirely.
 * <p>
 * Adds {@code X-RateLimit-*} headers to every response from the matched endpoints.
 * Returns HTTP 429 with {@code Retry-After} when the limit is exceeded.
 * <p>
 * <b>A bucket belongs to one endpoint and one client.</b> The key is
 * {@code endpoint + "|" + resolved client address}: with the path alone as the key, exhausting the
 * endpoint's budget blocked every other caller, and {@code X-RateLimit-Remaining} described a budget
 * nobody in particular owned. The address is the one {@link ClientIpResolver} returns, so a forwarded
 * header only counts when the peer is a trusted proxy.
 * <p>
 * <b>The map is bounded without a cache dependency, and the bound is behavioural.</b> One entry per
 * client per endpoint grows with the number of client addresses, which is exactly the quantity a
 * caller controls, so it cannot be left unbounded. Caffeine is not on the classpath and this filter
 * adds no dependency, so every {@value #SWEEP_INTERVAL_REQUESTS} requests the filter drops the entries
 * whose bucket is back at its full capacity. That is safe by construction rather than by argument: a
 * full bucket carries no history — its state is "capacity tokens available", which is precisely the
 * state of a bucket that had just been created — so a swept entry answers the next request exactly as
 * the entry it replaced would have, with the same limit and the same remaining budget. What the sweep
 * therefore bounds is the population of <em>idle</em> clients: a caller that keeps consuming keeps its
 * bucket away from full and keeps its history, which is the only history the limit is made of.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String HEADER_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RESET = "X-RateLimit-Reset";
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    private static final String APPLICATION_JSON = "application/json";

    /** Stands in for a missing socket address so the bucket key is still a function of the request. */
    private static final String UNKNOWN_CLIENT = "unknown";

    /** How many requests pass between two sweeps of the bucket map. */
    static final int SWEEP_INTERVAL_REQUESTS = 256;

    private final RateLimitProperties properties;

    /**
     * Holds a Bucket4j bucket along with its configured maximum request count
     * so we can report it in the {@code X-RateLimit-Limit} header. Keyed by
     * {@code endpoint + "|" + client address}.
     */
    private final Map<String, RateLimitedEndpoint> endpoints = new ConcurrentHashMap<>();

    private final AtomicLong requestCount = new AtomicLong();

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
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        var clientIp = ClientIpResolver.resolve(request, properties.getTrustedProxies());
        if (isWhitelisted(clientIp)) {
            filterChain.doFilter(request, response);
            return;
        }

        var endpointKey = resolveEndpointKey(request);
        var limit = properties.getEndpoints().get(endpointKey);
        if (limit == null) {
            // No rate limit configured for this path — allow through
            filterChain.doFilter(request, response);
            return;
        }

        var bucketKey = bucketKey(endpointKey, clientIp);
        var endpoint = endpoints.computeIfAbsent(bucketKey, key -> createEndpoint(limit));
        sweepIfDue();

        var probe = endpoint.bucket().tryConsumeAndReturnRemaining(1);
        var nowEpochSecond = Instant.now().getEpochSecond();

        response.setHeader(HEADER_LIMIT, String.valueOf(endpoint.maxRequests()));
        response.setHeader(HEADER_REMAINING, String.valueOf(probe.getRemainingTokens()));
        response.setHeader(
                HEADER_RESET, String.valueOf(nowEpochSecond + probe.getNanosToWaitForReset() / 1_000_000_000L));

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
            log.warn("Rate limit exceeded for endpoint [{}] from IP [{}]", endpointKey, clientIp);
        }
    }

    /**
     * Number of buckets currently held, one per endpoint and client. Package-visible so a test can
     * observe that the map is swept instead of growing without a bound.
     */
    int trackedEndpointCount() {
        return endpoints.size();
    }

    /**
     * Returns {@code true} if the resolved client address is whitelisted. The address comes from
     * {@link ClientIpResolver}, which only trusts forwarded headers coming from a trusted proxy, so a
     * caller cannot whitelist itself by sending a header.
     */
    private boolean isWhitelisted(String clientIp) {
        if (clientIp == null) {
            return false;
        }
        for (var whitelisted : properties.getInternalIpWhitelist()) {
            if (CidrMatcher.matches(clientIp, whitelisted)) {
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

    private static String bucketKey(String endpointKey, String clientIp) {
        return endpointKey + "|" + (clientIp == null ? UNKNOWN_CLIENT : clientIp);
    }

    /**
     * Builds the bucket for one client of one endpoint from that endpoint's configured limit.
     */
    private static RateLimitedEndpoint createEndpoint(RateLimitProperties.EndpointLimit limit) {
        var bandwidth = Bandwidth.builder()
                .capacity(limit.maxRequests())
                .refillIntervally(limit.maxRequests(), limit.duration())
                .build();
        var bucket = Bucket.builder().addLimit(bandwidth).build();
        return new RateLimitedEndpoint(bucket, limit.maxRequests());
    }

    /**
     * Drops every bucket that is back at its full capacity, once every
     * {@value #SWEEP_INTERVAL_REQUESTS} requests. The removal is the map's own atomic
     * {@code remove(key, value)}, so a request that consumes a token between the test and the removal
     * cannot lose its bucket: the conditional remove fails and the entry stays.
     */
    private void sweepIfDue() {
        if (requestCount.incrementAndGet() % SWEEP_INTERVAL_REQUESTS != 0) {
            return;
        }
        endpoints.forEach((key, endpoint) -> {
            if (endpoint.bucket().getAvailableTokens() >= endpoint.maxRequests()) {
                if (endpoints.remove(key, endpoint) && log.isDebugEnabled()) {
                    log.debug("Swept the idle rate-limit bucket [{}]", key);
                }
            }
        });
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
