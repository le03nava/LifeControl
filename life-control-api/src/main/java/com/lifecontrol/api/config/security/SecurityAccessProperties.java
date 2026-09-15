package com.lifecontrol.api.config.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security-related access properties.
 * <p>
 * Prefix: {@code app.security}
 */
@ConfigurationProperties(prefix = "app.security")
public class SecurityAccessProperties {

    /**
     * Addresses (IP or CIDR) whose {@code X-Forwarded-For} / {@code X-Real-IP}
     * headers are trusted. Only when the immediate peer (the reverse proxy / gateway)
     * matches one of these entries will the forwarded client address be honored —
     * otherwise the header is ignored and the socket peer address is used, preventing
     * clients from spoofing their IP.
     */
    private List<String> trustedProxies =
            List.of("127.0.0.1/32", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

    /**
     * Addresses (IP or CIDR) allowed to reach internal actuator endpoints such as
     * {@code /actuator/prometheus}. Defaults to loopback and private ranges so that
     * the in-network metrics scraper works while public exposure is denied.
     */
    private List<String> actuatorAllowedCidrs =
            List.of("127.0.0.1/32", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public List<String> getActuatorAllowedCidrs() {
        return actuatorAllowedCidrs;
    }

    public void setActuatorAllowedCidrs(List<String> actuatorAllowedCidrs) {
        this.actuatorAllowedCidrs = actuatorAllowedCidrs;
    }
}
