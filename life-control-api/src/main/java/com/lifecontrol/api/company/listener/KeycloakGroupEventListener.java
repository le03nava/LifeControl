package com.lifecontrol.api.company.listener;

import com.lifecontrol.api.company.event.CompanyCreatedEvent;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for {@link CompanyCreatedEvent} and creates a corresponding group in
 * Keycloak. Fires only after the company transaction commits successfully.
 * Group creation failures are logged but never propagated.
 */
@Component
public class KeycloakGroupEventListener {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakGroupEventListener.class);

    private final IdentityProvider identityProvider;

    public KeycloakGroupEventListener(IdentityProvider identityProvider) {
        this.identityProvider = identityProvider;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompanyCreated(CompanyCreatedEvent event) {
        var groupName = "company-" + sanitizeGroupName(event.getCompanyName());
        try {
            identityProvider.createCompanyGroup(groupName, event.getId().toString());
            logger.info("Keycloak group created for company: name={}, id={}", groupName, event.getId());
        } catch (IdentityProviderException e) {
            logger.warn("Failed to create Keycloak group for company: name={}, id={}, error={}",
                    groupName, event.getId(), e.getMessage());
        }
    }

    private static String sanitizeGroupName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
    }
}
