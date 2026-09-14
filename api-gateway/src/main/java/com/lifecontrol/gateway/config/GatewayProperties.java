package com.lifecontrol.gateway.config;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "lifecontrol.gateway")
public record GatewayProperties(
    @NotBlank String lifeControlApiUri,
    @NotBlank String keycloakInternalUri
) {
}
