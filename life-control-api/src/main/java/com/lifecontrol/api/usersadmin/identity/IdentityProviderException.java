package com.lifecontrol.api.usersadmin.identity;

/**
 * Sealed hierarchy for identity provider errors.
 * Prevents leaking provider-specific exceptions to the domain layer.
 */
public sealed class IdentityProviderException extends RuntimeException
        permits IdentityProviderNotFoundException,
                IdentityProviderConflictException,
                IdentityProviderConnectionException {

    protected IdentityProviderException(String message) {
        super(message);
    }

    protected IdentityProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
