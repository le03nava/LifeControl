package com.lifecontrol.api.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for matching an IP address against a CIDR block or an exact IP.
 * <p>
 * Supports both IPv4 and IPv6. A rule without a {@code /} is treated as an exact
 * match. Malformed rules or IPs fail closed (return {@code false}).
 * <p>
 * <b>Nothing reaches {@link InetAddress#getByName(String)} unless it is an address literal.</b> That
 * call resolves anything that is not a literal through DNS, so an unguarded matcher turns a
 * caller-written {@code X-Forwarded-For} hop into a resolver query and lets a name match whatever
 * address it happens to resolve to. {@link #looksLikeIp(String)} is the gate, and
 * {@link ClientIpResolver} reads it as the same rule: a hop that is not a literal is not an address.
 */
public final class CidrMatcher {

    private static final Logger log = LoggerFactory.getLogger(CidrMatcher.class);

    /**
     * An IPv6 literal: hex digits and colons. A string containing a colon can never be a DNS name,
     * which is what makes it safe to hand to the address parser — a malformed one fails there instead
     * of being looked up.
     */
    private static final String IPV6_LITERAL = "[0-9a-fA-F:]+";

    private CidrMatcher() {}

    /**
     * Returns {@code true} when {@code value} is an IP address literal — a numeric IPv4 address or an
     * IPv6 address — and {@code false} for anything a name service could be asked about.
     */
    static boolean looksLikeIp(String value) {
        if (value == null) {
            return false;
        }
        var candidate = value.trim();
        if (candidate.isEmpty()) {
            return false;
        }
        if (candidate.indexOf(':') >= 0) {
            return candidate.length() <= 45 && candidate.matches(IPV6_LITERAL);
        }
        return isIpv4Literal(candidate);
    }

    /**
     * Returns {@code true} if {@code ip} belongs to {@code rule}, which may be either
     * a CIDR block (e.g. {@code 10.0.0.0/8}) or a single IP (e.g. {@code 127.0.0.1}).
     */
    public static boolean matches(String ip, String rule) {
        if (ip == null || rule == null || rule.isBlank() || !looksLikeIp(ip)) {
            return false;
        }
        if (!rule.contains("/")) {
            return rule.equals(ip);
        }

        try {
            var parts = rule.split("/");
            if (parts.length != 2 || !looksLikeIp(parts[0])) {
                return false;
            }

            var addrBytes = InetAddress.getByName(ip.trim()).getAddress();
            var cidrBytes = InetAddress.getByName(parts[0]).getAddress();
            var prefixLen = Integer.parseInt(parts[1]);

            if (addrBytes.length != cidrBytes.length || prefixLen < 0 || prefixLen > addrBytes.length * 8) {
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
            log.warn("Failed to parse CIDR rule [{}] for IP [{}]", rule, ip, e);
            return false;
        }
    }

    /** four decimal octets, each 0-255, so the parser reads it as a literal and never as a name. */
    private static boolean isIpv4Literal(String candidate) {
        var parts = candidate.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (var part = 0; part < parts.length; part++) {
            var octet = parts[part];
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (var i = 0; i < octet.length(); i++) {
                var digit = octet.charAt(i);
                if (digit < '0' || digit > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }
}
