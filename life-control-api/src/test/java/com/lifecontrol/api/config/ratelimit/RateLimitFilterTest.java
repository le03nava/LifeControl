package com.lifecontrol.api.config.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties.EndpointLimit;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitFilter Tests")
class RateLimitFilterTest {

    @Nested
    @DisplayName("shouldNotFilter")
    class ShouldNotFilterTests {

        private RateLimitProperties properties;
        private RateLimitFilter filter;

        @BeforeEach
        void setUp() {
            properties = new RateLimitProperties();
            filter = new RateLimitFilter(properties);
        }

        @Test
        @DisplayName("should return true when rate limiting is disabled")
        void shouldNotFilter_WhenDisabled() {
            properties.setEnabled(false);
            var request = new MockHttpServletRequest("GET", "/api/users-admin/users");

            var result = filter.shouldNotFilter(request);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return true for non-admin paths")
        void shouldNotFilter_NonAdminPath() {
            properties.setEnabled(true);
            var request = new MockHttpServletRequest("GET", "/api/companies");

            var result = filter.shouldNotFilter(request);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false for admin paths when enabled")
        void shouldNotFilter_AdminPath_WhenEnabled() {
            properties.setEnabled(true);
            var request = new MockHttpServletRequest("GET", "/api/users-admin/users");

            var result = filter.shouldNotFilter(request);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("doFilterInternal")
    class DoFilterInternalTests {

        private RateLimitProperties properties;
        private RateLimitFilter filter;

        @BeforeEach
        void setUp() {
            properties = new RateLimitProperties();
            properties.setEnabled(true);
            properties.setInternalIpWhitelist(List.of());
            var endpoints = new ConcurrentHashMap<String, EndpointLimit>();
            endpoints.put("/api/users-admin/users", new EndpointLimit(1, Duration.ofMinutes(1)));
            properties.setEndpoints(endpoints);

            filter = new RateLimitFilter(properties);
        }

        @Test
        @DisplayName("should allow request within rate limit and add X-RateLimit headers")
        void requestWithinLimit_Returns200_WithHeaders() throws Exception {
            var request = new MockHttpServletRequest("GET", "/api/users-admin/users");
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("1");
            assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
            assertThat(response.getHeader("X-RateLimit-Reset")).isNotNull();
            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("should return 429 when rate limit is exceeded")
        void rateLimitExceeded_Returns429() throws Exception {
            // Consume the only token with first request
            var request = new MockHttpServletRequest("GET", "/api/users-admin/users");
            filter.doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class));

            // Second request exceeds the limit
            var secondRequest = new MockHttpServletRequest("GET", "/api/users-admin/users");
            var secondResponse = new MockHttpServletResponse();
            filter.doFilterInternal(secondRequest, secondResponse, mock(FilterChain.class));

            assertThat(secondResponse.getStatus()).isEqualTo(429);
            assertThat(secondResponse.getHeader("Retry-After")).isNotNull();
            assertThat(secondResponse.getContentType()).isEqualTo("application/json");
            assertThat(secondResponse.getContentAsString()).contains("Rate limit exceeded");
        }

        @Test
        @DisplayName("should allow request when no endpoint configuration exists")
        void requestWithoutEndpointConfig_PassesThrough() throws Exception {
            var request = new MockHttpServletRequest("GET", "/api/users-admin/unknown");
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(200);
            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("should set correct JSON error body on 429")
        void rateLimitExceeded_ReturnsJsonBody() throws Exception {
            // Consume the only token
            var request = new MockHttpServletRequest("GET", "/api/users-admin/users");
            filter.doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class));

            var secondRequest = new MockHttpServletRequest("GET", "/api/users-admin/users");
            var secondResponse = new MockHttpServletResponse();
            filter.doFilterInternal(secondRequest, secondResponse, mock(FilterChain.class));

            assertThat(secondResponse.getContentType()).isEqualTo("application/json");
            assertThat(secondResponse.getContentAsString()).contains("\"status\":429");
            assertThat(secondResponse.getContentAsString()).contains("\"error\":\"Too Many Requests\"");
            assertThat(secondResponse.getContentAsString()).contains("\"message\"");
        }
    }

    @Nested
    @DisplayName("per-client buckets")
    class PerClientBucketTests {

        private RateLimitFilter filter;

        @BeforeEach
        void setUp() {
            var properties = new RateLimitProperties();
            properties.setEnabled(true);
            properties.setInternalIpWhitelist(List.of());
            properties.setTrustedProxies(List.of());
            var endpoints = new ConcurrentHashMap<String, EndpointLimit>();
            endpoints.put("/api/users-admin/users", new EndpointLimit(1, Duration.ofMinutes(1)));
            properties.setEndpoints(endpoints);

            filter = new RateLimitFilter(properties);
        }

        private MockHttpServletResponse requestFrom(String clientIp) throws Exception {
            var request = new MockHttpServletRequest("GET", "/api/users-admin/users");
            request.setRemoteAddr(clientIp);
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, mock(FilterChain.class));
            return response;
        }

        @Test
        @DisplayName("two distinct clients on one endpoint report independent budgets")
        void twoClients_ReportIndependentBudgets() throws Exception {
            var firstClient = requestFrom("203.0.113.5");
            var secondClient = requestFrom("198.51.100.9");

            assertThat(firstClient.getStatus()).isEqualTo(200);
            assertThat(firstClient.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
            assertThat(secondClient.getStatus())
                    .as("the second client must not pay for the first client's bucket")
                    .isEqualTo(200);
            assertThat(secondClient.getHeader("X-RateLimit-Remaining"))
                    .as("the remaining budget is the caller's own")
                    .isEqualTo("0");
        }

        @Test
        @DisplayName("exhausting one client leaves the other's quota intact")
        void exhaustingOneClient_LeavesTheOtherIntact() throws Exception {
            assertThat(requestFrom("203.0.113.5").getStatus()).isEqualTo(200);
            assertThat(requestFrom("203.0.113.5").getStatus()).isEqualTo(429);

            assertThat(requestFrom("198.51.100.9").getStatus())
                    .as("a 429 for one caller must not become a 429 for another")
                    .isEqualTo(200);
        }
    }

    /**
     * S2: the shipped default trusts loopback and nothing else, so a private-range peer can no longer
     * forward a loopback client onto the whitelist.
     */
    @Nested
    @DisplayName("trusted proxy defaults")
    class TrustedProxyDefaultsTests {

        @Test
        @DisplayName("defaults to loopback only")
        void defaultsToLoopbackOnly() {
            assertThat(new RateLimitProperties().getTrustedProxies()).containsExactly("127.0.0.1/32", "::1/128");
        }

        @Test
        @DisplayName("a private-range peer cannot forward a loopback client past the limit")
        void privateRangePeer_CannotForwardLoopbackClient() throws Exception {
            var properties = new RateLimitProperties();
            properties.setEnabled(true);
            properties.setInternalIpWhitelist(List.of("127.0.0.1"));
            var endpoints = new ConcurrentHashMap<String, EndpointLimit>();
            endpoints.put("/api/users-admin/users", new EndpointLimit(1, Duration.ofMinutes(1)));
            properties.setEndpoints(endpoints);

            var filter = new RateLimitFilter(properties);
            for (var i = 0; i < 2; i++) {
                var request = new MockHttpServletRequest("GET", "/api/users-admin/users");
                request.setRemoteAddr("10.0.0.5");
                request.addHeader("X-Forwarded-For", "127.0.0.1");
                filter.doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class));
            }

            var thirdRequest = new MockHttpServletRequest("GET", "/api/users-admin/users");
            thirdRequest.setRemoteAddr("10.0.0.5");
            thirdRequest.addHeader("X-Forwarded-For", "127.0.0.1");
            var thirdResponse = new MockHttpServletResponse();
            filter.doFilterInternal(thirdRequest, thirdResponse, mock(FilterChain.class));

            assertThat(thirdResponse.getStatus())
                    .as("a 10.x peer is not a trusted proxy, so its forwarded header is ignored")
                    .isEqualTo(429);
        }
    }

    /**
     * The bucket map is keyed by endpoint and client, so its size is the number of client addresses a
     * caller can invent. The bound is behavioural: a bucket back at its full capacity carries no
     * history, so sweeping it changes no answer. These two cases pin both halves — a full bucket goes,
     * one that still owes history stays.
     */
    @Nested
    @DisplayName("bucket map bound")
    class BucketMapBoundTests {

        private RateLimitFilter filter;

        @BeforeEach
        void setUp() {
            var properties = new RateLimitProperties();
            properties.setEnabled(true);
            properties.setInternalIpWhitelist(List.of());
            properties.setTrustedProxies(List.of());
            var endpoints = new ConcurrentHashMap<String, EndpointLimit>();
            // Short window: a consumed bucket is back at capacity inside the test.
            endpoints.put("/api/users-admin/users", new EndpointLimit(2, Duration.ofMillis(20)));
            // Long window and room to spare: this is the client that drives the sweep cadence, and
            // its own bucket must stay away from full for the whole run.
            endpoints.put("/api/users-admin/roles", new EndpointLimit(500, Duration.ofMinutes(1)));
            properties.setEndpoints(endpoints);

            filter = new RateLimitFilter(properties);
        }

        private MockHttpServletResponse request(String path, String clientIp) throws Exception {
            var request = new MockHttpServletRequest("GET", path);
            request.setRemoteAddr(clientIp);
            var response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, mock(FilterChain.class));
            return response;
        }

        @Test
        @DisplayName("sweeps a bucket that is back at full capacity")
        void sweepsIdleBucket() throws Exception {
            request("/api/users-admin/users", "203.0.113.5");
            assertThat(filter.trackedEndpointCount()).isEqualTo(1);
            Thread.sleep(80);

            for (var i = 0; i < RateLimitFilter.SWEEP_INTERVAL_REQUESTS; i++) {
                request("/api/users-admin/roles", "198.51.100.9");
            }

            assertThat(filter.trackedEndpointCount())
                    .as("a full bucket answers exactly what a fresh one would, so it is dropped")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("keeps a bucket that still carries history")
        void keepsBucketWithHistory() throws Exception {
            var properties = new RateLimitProperties();
            properties.setEnabled(true);
            properties.setInternalIpWhitelist(List.of());
            properties.setTrustedProxies(List.of());
            var endpoints = new ConcurrentHashMap<String, EndpointLimit>();
            endpoints.put("/api/users-admin/users", new EndpointLimit(2, Duration.ofMinutes(1)));
            endpoints.put("/api/users-admin/roles", new EndpointLimit(500, Duration.ofMinutes(1)));
            properties.setEndpoints(endpoints);
            var longWindowFilter = new RateLimitFilter(properties);

            var idle = new MockHttpServletRequest("GET", "/api/users-admin/users");
            idle.setRemoteAddr("203.0.113.5");
            longWindowFilter.doFilterInternal(idle, new MockHttpServletResponse(), mock(FilterChain.class));

            for (var i = 0; i < RateLimitFilter.SWEEP_INTERVAL_REQUESTS; i++) {
                var driving = new MockHttpServletRequest("GET", "/api/users-admin/roles");
                driving.setRemoteAddr("198.51.100.9");
                longWindowFilter.doFilterInternal(driving, new MockHttpServletResponse(), mock(FilterChain.class));
            }

            assertThat(longWindowFilter.trackedEndpointCount())
                    .as("a bucket with a spent token is history, not an idle entry")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("client IP trust")
    class ClientIpTrustTests {

        private RateLimitProperties properties;
        private RateLimitFilter filter;

        @BeforeEach
        void setUp() {
            properties = new RateLimitProperties();
            properties.setEnabled(true);
            // The peer in the honoured case below is a private-range proxy, so it has to be declared:
            // the shipped default trusts loopback only.
            properties.setTrustedProxies(List.of("10.0.0.0/8"));
            // 127.0.0.1 is whitelisted and would bypass the limit if trusted
            properties.setInternalIpWhitelist(List.of("127.0.0.1"));
            var endpoints = new ConcurrentHashMap<String, EndpointLimit>();
            endpoints.put("/api/users-admin/users", new EndpointLimit(1, Duration.ofMinutes(1)));
            properties.setEndpoints(endpoints);

            filter = new RateLimitFilter(properties);
        }

        @Test
        @DisplayName("ignores spoofed X-Forwarded-For from an untrusted peer")
        void spoofedForwardedHeader_FromUntrustedPeer_IsIgnored() throws Exception {
            // Public client pretends to be the whitelisted loopback address
            var first = new MockHttpServletRequest("GET", "/api/users-admin/users");
            first.setRemoteAddr("203.0.113.5");
            first.addHeader("X-Forwarded-For", "127.0.0.1");
            filter.doFilterInternal(first, new MockHttpServletResponse(), mock(FilterChain.class));

            var second = new MockHttpServletRequest("GET", "/api/users-admin/users");
            second.setRemoteAddr("203.0.113.5");
            second.addHeader("X-Forwarded-For", "127.0.0.1");
            var secondResponse = new MockHttpServletResponse();
            filter.doFilterInternal(second, secondResponse, mock(FilterChain.class));

            assertThat(secondResponse.getStatus())
                    .as("spoofed header must not whitelist the client")
                    .isEqualTo(429);
        }

        @Test
        @DisplayName("honors X-Forwarded-For coming from a trusted proxy")
        void forwardedHeader_FromTrustedProxy_IsHonored() throws Exception {
            // Trusted private-range proxy forwards the whitelisted loopback client
            for (var i = 0; i < 2; i++) {
                var request = new MockHttpServletRequest("GET", "/api/users-admin/users");
                request.setRemoteAddr("10.0.0.1");
                request.addHeader("X-Forwarded-For", "127.0.0.1");
                var response = new MockHttpServletResponse();
                var chain = mock(FilterChain.class);
                filter.doFilterInternal(request, response, chain);

                assertThat(response.getStatus()).isEqualTo(200);
                verify(chain).doFilter(request, response);
            }
        }
    }
}
