package com.lifecontrol.api.hr.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.UUID;

/**
 * 409 category for an attempt to change the email of an employee that is already linked to a
 * Keycloak account (decision T9).
 *
 * <p>Once {@code keycloakUserId} is set the address is a login identity: renaming it here would
 * desynchronize the Keycloak username, which is the full email (decision D2). The rule freezes the
 * address for the record path; the access-provisioning flow owns the account.</p>
 */
public class EmployeeEmailFrozenException extends ConflictException {

    public EmployeeEmailFrozenException(UUID id) {
        super("Employee " + id + " email is frozen because the record is linked to a Keycloak account");
    }
}
