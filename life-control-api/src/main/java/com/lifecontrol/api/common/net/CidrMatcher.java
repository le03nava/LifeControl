package com.lifecontrol.api.common.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Utility for matching an IP address against a CIDR block or an exact IP.
 * <p>
 * Supports both IPv4 and IPv6. A rule without a {@code /} is treated as an exact
 * match. Malformed rules or IPs fail closed (return {@code false}).
 */
public final class CidrMatcher {

    private static final Logger log = LoggerFactory.getLogger(CidrMatcher.class);

    private CidrMatcher() {
    }

    /**
     * Returns {@code true} if {@code ip} belongs to {@code rule}, which may be either
     * a CIDR block (e.g. {@code 10.0.0.0/8}) or a single IP (e.g. {@code 127.0.0.1}).
     */
    public static boolean matches(String ip, String rule) {
        if (ip == null || rule == null || rule.isBlank()) {
            return false;
        }
        if (!rule.contains("/")) {
            return rule.equals(ip);
        }

        try {
            var addrBytes = InetAddress.getByName(ip).getAddress();

            var parts = rule.split("/");
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
}
