package com.lifecontrol.api.config.security;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Validates the {@code iss} claim of a JWT against a configured allowlist of
 * issuers. Trailing slashes are normalized so
 * {@code https://host/realms/x} and {@code https://host/realms/x/} are treated
 * as the same issuer.
 *
 * <p>This mirrors the API Gateway behavior: the browser obtains tokens with the
 * public Keycloak issuer (e.g. {@code http://localhost:8181/...}) while the
 * signature is still verified against the internal JWK set.
 */
final class JwtIssuerAllowlistValidator implements OAuth2TokenValidator<Jwt> {

    private static final String INVALID_ISSUER = "invalid_issuer";

    private final List<String> allowedIssuers;

    JwtIssuerAllowlistValidator(List<String> allowedIssuers) {
        this.allowedIssuers = allowedIssuers.stream()
                .map(JwtIssuerAllowlistValidator::normalize)
                .toList();
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String issuer =
                token.getIssuer() == null ? null : normalize(token.getIssuer().toString());
        if (issuer != null && allowedIssuers.contains(issuer)) {
            return OAuth2TokenValidatorResult.success();
        }
        OAuth2Error error = new OAuth2Error(
                INVALID_ISSUER, "The issuer claim is not in the configured allowlist: " + token.getIssuer(), null);
        return OAuth2TokenValidatorResult.failure(error);
    }

    private static String normalize(String issuer) {
        return issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }
}
