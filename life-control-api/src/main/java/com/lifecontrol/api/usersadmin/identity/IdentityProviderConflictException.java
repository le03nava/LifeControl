package com.lifecontrol.api.usersadmin.identity;

public final class IdentityProviderConflictException extends IdentityProviderException {

    public IdentityProviderConflictException(String message) {
        super(message);
    }

    public IdentityProviderConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
