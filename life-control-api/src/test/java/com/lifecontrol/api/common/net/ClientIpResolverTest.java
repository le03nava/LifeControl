package com.lifecontrol.api.common.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("ClientIpResolver Tests")
class ClientIpResolverTest {

    private static final List<String> TRUSTED_PROXIES = List.of("10.0.0.0/8");

    private MockHttpServletRequest requestFrom(String remoteAddr) {
        var request = new MockHttpServletRequest("GET", "/api/test");
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Nested
    @DisplayName("untrusted peer")
    class UntrustedPeerTests {

        @Test
        @DisplayName("ignores X-Forwarded-For")
        void ignoresForwardedFor() {
            var request = requestFrom("203.0.113.5");
            request.addHeader("X-Forwarded-For", "127.0.0.1");

            assertThat(ClientIpResolver.resolve(request, TRUSTED_PROXIES)).isEqualTo("203.0.113.5");
        }

        @Test
        @DisplayName("ignores X-Real-IP")
        void ignoresRealIp() {
            var request = requestFrom("203.0.113.5");
            request.addHeader("X-Real-IP", "127.0.0.1");

            assertThat(ClientIpResolver.resolve(request, TRUSTED_PROXIES)).isEqualTo("203.0.113.5");
        }
    }

    @Nested
    @DisplayName("trusted proxy")
    class TrustedProxyTests {

        @Test
        @DisplayName("honors the first X-Forwarded-For hop")
        void honorsFirstForwardedHop() {
            var request = requestFrom("10.0.0.1");
            request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.1");

            assertThat(ClientIpResolver.resolve(request, TRUSTED_PROXIES)).isEqualTo("198.51.100.7");
        }

        @Test
        @DisplayName("falls back to X-Real-IP when X-Forwarded-For is absent")
        void fallsBackToRealIp() {
            var request = requestFrom("10.0.0.1");
            request.addHeader("X-Real-IP", "198.51.100.7");

            assertThat(ClientIpResolver.resolve(request, TRUSTED_PROXIES)).isEqualTo("198.51.100.7");
        }

        @Test
        @DisplayName("returns the peer address when no forwarded headers are present")
        void returnsPeerWhenNoHeaders() {
            var request = requestFrom("10.0.0.1");

            assertThat(ClientIpResolver.resolve(request, TRUSTED_PROXIES)).isEqualTo("10.0.0.1");
        }
    }
}
