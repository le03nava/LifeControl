package com.lifecontrol.api.common.net;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CidrMatcher Tests")
class CidrMatcherTest {

    @Nested
    @DisplayName("exact IP rules")
    class ExactIpTests {

        @Test
        @DisplayName("matches an identical IP")
        void matchesExactIp() {
            assertThat(CidrMatcher.matches("127.0.0.1", "127.0.0.1")).isTrue();
        }

        @Test
        @DisplayName("does not match a different IP")
        void doesNotMatchDifferentIp() {
            assertThat(CidrMatcher.matches("127.0.0.2", "127.0.0.1")).isFalse();
        }
    }

    @Nested
    @DisplayName("CIDR rules")
    class CidrTests {

        @Test
        @DisplayName("matches an address inside the block")
        void matchesInsideBlock() {
            assertThat(CidrMatcher.matches("10.20.30.40", "10.0.0.0/8")).isTrue();
        }

        @Test
        @DisplayName("does not match an address outside the block")
        void doesNotMatchOutsideBlock() {
            assertThat(CidrMatcher.matches("11.0.0.1", "10.0.0.0/8")).isFalse();
        }

        @Test
        @DisplayName("matches on a non-octet-aligned prefix")
        void matchesNonOctetAlignedPrefix() {
            assertThat(CidrMatcher.matches("192.168.1.130", "192.168.1.128/25")).isTrue();
            assertThat(CidrMatcher.matches("192.168.1.127", "192.168.1.128/25")).isFalse();
        }

        @Test
        @DisplayName("matches a /32 exact host block")
        void matchesHostBlock() {
            assertThat(CidrMatcher.matches("8.8.8.8", "8.8.8.8/32")).isTrue();
        }

        @Test
        @DisplayName("matches IPv6 loopback")
        void matchesIpv6Loopback() {
            assertThat(CidrMatcher.matches("::1", "::1/128")).isTrue();
        }

        @Test
        @DisplayName("does not match across address families")
        void doesNotMatchAcrossFamilies() {
            assertThat(CidrMatcher.matches("127.0.0.1", "::1/128")).isFalse();
        }
    }

    @Nested
    @DisplayName("IP literal detection")
    class IpLiteralTests {

        @Test
        @DisplayName("accepts IPv4 and IPv6 literals")
        void acceptsLiterals() {
            assertThat(CidrMatcher.looksLikeIp("127.0.0.1")).isTrue();
            assertThat(CidrMatcher.looksLikeIp("255.255.255.255")).isTrue();
            assertThat(CidrMatcher.looksLikeIp("::1")).isTrue();
            assertThat(CidrMatcher.looksLikeIp("2001:db8::7")).isTrue();
            assertThat(CidrMatcher.looksLikeIp("  ::1  ")).isTrue();
        }

        @Test
        @DisplayName("rejects names and malformed addresses")
        void rejectsNames() {
            assertThat(CidrMatcher.looksLikeIp("localhost")).isFalse();
            assertThat(CidrMatcher.looksLikeIp("evil.example.com")).isFalse();
            assertThat(CidrMatcher.looksLikeIp("127.0.0.1.example.com")).isFalse();
            assertThat(CidrMatcher.looksLikeIp("999.999.999.999")).isFalse();
            assertThat(CidrMatcher.looksLikeIp("10.0.0")).isFalse();
            assertThat(CidrMatcher.looksLikeIp("")).isFalse();
            assertThat(CidrMatcher.looksLikeIp(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("invalid input")
    class InvalidInputTests {

        @Test
        @DisplayName("refuses a hostname instead of resolving it")
        void refusesHostname() {
            // "localhost" resolves to 127.0.0.1, so InetAddress.getByName would have matched it: a
            // caller-written hop could name a hostname and reach any address DNS answers for it.
            assertThat(CidrMatcher.matches("localhost", "127.0.0.1/32")).isFalse();
            assertThat(CidrMatcher.matches("localhost", "127.0.0.1")).isFalse();
        }

        @Test
        @DisplayName("fails closed on malformed rules")
        void failsClosedOnMalformedRule() {
            assertThat(CidrMatcher.matches("10.0.0.1", "10.0.0.0/notanumber")).isFalse();
        }

        @Test
        @DisplayName("fails closed on null or blank input")
        void failsClosedOnNull() {
            assertThat(CidrMatcher.matches(null, "10.0.0.0/8")).isFalse();
            assertThat(CidrMatcher.matches("10.0.0.1", null)).isFalse();
            assertThat(CidrMatcher.matches("10.0.0.1", "  ")).isFalse();
        }
    }
}
