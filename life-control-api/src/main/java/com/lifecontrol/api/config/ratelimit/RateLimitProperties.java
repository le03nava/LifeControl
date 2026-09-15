package com.lifecontrol.api.config.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for API rate limiting.
 * <p>
 * Controls rate limits per endpoint, internal IP whitelist, and global enabled flag.
 * Prefix: {@code app.rate-limit}
 * <p>
 * Example configuration:
 * <pre>
 * app.rate-limit.enabled=true
 * app.rate-limit.internal-ip-whitelist=127.0.0.1,10.0.0.0/8
 * app.rate-limit.endpoints./api/users-admin/users.max-requests=60
 * app.rate-limit.endpoints./api/users-admin/users.duration=1m
 * app.rate-limit.endpoints./api/users-admin/login.max-requests=10
 * app.rate-limit.endpoints./api/users-admin/login.duration=1m
 * </pre>
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    private List<String> internalIpWhitelist = List.of();

    /**
     * Addresses (IP or CIDR) whose {@code X-Forwarded-For} / {@code X-Real-IP}
     * headers may be trusted. Only when the immediate peer matches one of these
     * entries is the forwarded client address honored; otherwise the socket peer
     * address is used, preventing clients from spoofing the whitelist.
     */
    private List<String> trustedProxies = List.of(
            "127.0.0.1/32", "::1/128",
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

    private Map<String, EndpointLimit> endpoints = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getInternalIpWhitelist() {
        return internalIpWhitelist;
    }

    public void setInternalIpWhitelist(List<String> internalIpWhitelist) {
        this.internalIpWhitelist = internalIpWhitelist;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public Map<String, EndpointLimit> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(Map<String, EndpointLimit> endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * Represents the rate limit configuration for a single endpoint.
     *
     * @param maxRequests maximum number of requests allowed within the duration
     * @param duration    the time window for the limit
     */
    public record EndpointLimit(int maxRequests, Duration duration) {

        public EndpointLimit {
            if (maxRequests < 1) {
                throw new IllegalArgumentException("maxRequests must be >= 1");
            }
            if (duration == null || duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("duration must be positive");
            }
        }
    }
}
