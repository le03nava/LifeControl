package com.lifecontrol.api.usersadmin.identity;

public final class IdentityProviderConnectionException extends IdentityProviderException {

    public IdentityProviderConnectionException(String message) {
        super(message);
    }

    public IdentityProviderConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
