package com.lifecontrol.api.config.ratelimit;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties.EndpointLimit;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
}
