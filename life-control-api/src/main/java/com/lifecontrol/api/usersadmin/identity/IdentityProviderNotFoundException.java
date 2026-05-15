package com.lifecontrol.api.usersadmin.identity;

public final class IdentityProviderNotFoundException extends IdentityProviderException {

    public IdentityProviderNotFoundException(String message) {
        super(message);
    }

    public IdentityProviderNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
