package com.lifecontrol.api.provisioning.dto;

import com.lifecontrol.api.provisioning.model.AccountProvisioningOutcome;

/**
 * The outcome of ensuring (and optionally inviting) an employee's Keycloak account.
 *
 * @param keycloakUserId the resolved identity-provider account id, never null on a successful call
 * @param outcome which path the account lifecycle took
 */
public record AccountProvisioningResult(String keycloakUserId, AccountProvisioningOutcome outcome) {}
