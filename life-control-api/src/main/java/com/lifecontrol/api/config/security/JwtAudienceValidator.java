package com.lifecontrol.api.config.security;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Validates the {@code aud} claim of a JWT against the audiences this API serves.
 *
 * <p>An issuer allowlist alone accepts every token minted by the realm, by any of its clients, for any
 * other resource in it. {@code aud} is the claim that says who the token was minted <em>for</em>, so a
 * token that does not name this API is not this API's token, whoever signed it.
 *
 * <p>A missing {@code aud} is a rejection and not a pass: treating an absent claim as "no constraint
 * declared" would let any token minted without one through, which is the state this validator exists
 * to end.
 */
final class JwtAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final String INVALID_AUDIENCE = "invalid_audience";

    private final List<String> allowedAudiences;

    JwtAudienceValidator(List<String> allowedAudiences) {
        this.allowedAudiences = List.copyOf(allowedAudiences);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audience = token.getAudience();
        if (audience != null && audience.stream().anyMatch(allowedAudiences::contains)) {
            return OAuth2TokenValidatorResult.success();
        }
        OAuth2Error error = new OAuth2Error(
                INVALID_AUDIENCE,
                "The aud claim does not name any configured audience: " + audience + " (accepted: " + allowedAudiences
                        + ")",
                null);
        return OAuth2TokenValidatorResult.failure(error);
    }
}
