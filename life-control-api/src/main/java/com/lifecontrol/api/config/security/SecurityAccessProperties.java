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
     * <p>
     * The shipped default is loopback and nothing else: a private range is not a proxy
     * declaration, it is a network. A deployment that has a real proxy declares it through
     * {@code app.security.trusted-proxies} (bound to {@code TRUSTED_PROXIES} in
     * {@code application.properties}).
     */
    private List<String> trustedProxies = List.of("127.0.0.1/32", "::1/128");

    /**
     * Addresses (IP or CIDR) allowed to reach internal actuator endpoints such as
     * {@code /actuator/prometheus}. Defaults to loopback so that a scraper running on the
     * same host works while every other address is denied; a deployment that scrapes over
     * the container network declares that network through
     * {@code app.security.actuator.allowed-cidrs} (bound to {@code ACTUATOR_ALLOWED_CIDRS}).
     */
    private List<String> actuatorAllowedCidrs = List.of("127.0.0.1/32", "::1/128");

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
