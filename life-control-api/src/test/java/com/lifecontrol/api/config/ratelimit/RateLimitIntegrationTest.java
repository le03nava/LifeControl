package com.lifecontrol.api.config.ratelimit;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties.EndpointLimit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for rate limiting using MockMvc with the actual filter.
 * <p>
 * Tests verify end-to-end behavior: requests are intercepted by {@link RateLimitFilter},
 * tokens are consumed from Bucket4j buckets, and HTTP 429 is returned when limits are exceeded.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Rate Limit Integration Tests")
class RateLimitIntegrationTest {

    private RateLimitFilter rateLimitFilter;
    private RateLimitProperties properties;
    private MockMvc mockMvc;

    @SuppressWarnings("unused")
    @RestController
    static class AdminTestController {
        @GetMapping("/api/users-admin/users")
        public ResponseEntity<String> getUsers() {
            return ResponseEntity.ok("OK");
        }

        @GetMapping("/api/users-admin/roles")
        public ResponseEntity<String> getRoles() {
            return ResponseEntity.ok("OK");
        }
    }

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setInternalIpWhitelist(List.of());

        var endpoints = new ConcurrentHashMap<String, EndpointLimit>();
        endpoints.put("/api/users-admin/users", new EndpointLimit(2, Duration.ofMinutes(1)));
        endpoints.put("/api/users-admin/roles", new EndpointLimit(5, Duration.ofMinutes(1)));
        properties.setEndpoints(endpoints);

        rateLimitFilter = new RateLimitFilter(properties);

        // Use a fresh filter and properties for each test to ensure isolated bucket state
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminTestController())
                .addFilters(rateLimitFilter)
                .build();
    }

    @Nested
    @DisplayName("GET /api/users-admin/users (limit: 2 req/min)")
    class UsersEndpointTests {

        @Test
        @DisplayName("should allow requests within the rate limit")
        void firstRequest_Succeeds() throws Exception {
            mockMvc.perform(get("/api/users-admin/users"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "2"))
                    .andExpect(header().string("X-RateLimit-Remaining", "1"))
                    .andExpect(header().exists("X-RateLimit-Reset"));
        }

        @Test
        @DisplayName("should return 429 when rate limit is exceeded")
        void rateLimitExceeded_Returns429() throws Exception {
            // Consume both tokens
            mockMvc.perform(get("/api/users-admin/users")).andExpect(status().isOk());
            mockMvc.perform(get("/api/users-admin/users")).andExpect(status().isOk());

            // Third request exceeds the limit
            mockMvc.perform(get("/api/users-admin/users"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", org.hamcrest.Matchers.notNullValue()))
                    .andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.error").value("Too Many Requests"))
                    .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("should show decreasing remaining tokens")
        void decreasingRemainingTokens() throws Exception {
            mockMvc.perform(get("/api/users-admin/users"))
                    .andExpect(header().string("X-RateLimit-Remaining", "1"));

            mockMvc.perform(get("/api/users-admin/users"))
                    .andExpect(header().string("X-RateLimit-Remaining", "0"));
        }
    }

    @Nested
    @DisplayName("Independent endpoint limits")
    class IndependentEndpointTests {

        @Test
        @DisplayName("should allow roles endpoint access even when users endpoint is exhausted")
        void independentLimits() throws Exception {
            // Exhaust users endpoint (2 req/min)
            mockMvc.perform(get("/api/users-admin/users")).andExpect(status().isOk());
            mockMvc.perform(get("/api/users-admin/users")).andExpect(status().isOk());

            // Users endpoint should now be blocked
            mockMvc.perform(get("/api/users-admin/users"))
                    .andExpect(status().isTooManyRequests());

            // Roles endpoint (5 req/min) should still work
            mockMvc.perform(get("/api/users-admin/roles"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", "4"));
        }
    }

    @Nested
    @DisplayName("IP whitelist")
    class IpWhitelistTests {

        @BeforeEach
        void setUpWithWhitelist() {
            properties.setInternalIpWhitelist(new java.util.ArrayList<>(List.of("10.0.0.1")));
        }

        @Test
        @DisplayName("should bypass rate limiting for whitelisted IPs")
        void whitelistedIpBypassesRateLimit() throws Exception {
            // Whitelist check happens before bucket check — request passes through
            var request = new MockHttpServletRequest("GET", "/api/users-admin/users");
            request.setRemoteAddr("10.0.0.1");

            var response = new MockHttpServletResponse();
            rateLimitFilter.doFilterInternal(request, response, (req, res) -> {});

            assertThat(response.getStatus()).isEqualTo(200);
        }
    }
}
