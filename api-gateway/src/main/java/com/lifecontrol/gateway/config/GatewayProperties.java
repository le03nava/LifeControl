package com.lifecontrol.gateway.config;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "lifecontrol.gateway")
public record GatewayProperties(
    @NotBlank String lifeControlApiUri,
    @NotBlank String keycloakInternalUri,
    @NotEmpty List<String> allowedIssuers,
    @NotEmpty List<String> allowedOrigins
) {
}
