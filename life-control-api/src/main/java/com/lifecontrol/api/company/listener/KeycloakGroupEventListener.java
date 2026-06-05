package com.lifecontrol.api.company.listener;

import com.lifecontrol.api.company.event.CompanyCountryCreatedEvent;
import com.lifecontrol.api.company.event.CompanyCreatedEvent;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * Listens for {@link CompanyCreatedEvent} and creates a corresponding group in
 * Keycloak. Fires only after the company transaction commits successfully.
 * Group creation failures are logged but never propagated.
 */
@Component
public class KeycloakGroupEventListener {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakGroupEventListener.class);

    private static final String COMPANY_GROUP_PREFIX = "company-";
    private static final String COMPANY_ROLE_NAME = "life-control-company";
    private static final String COMPANY_CLIENT_ID = "life-control-client";

    private static final String COUNTRY_GROUP_PREFIX = "lc-company-country-";
    private static final String COUNTRY_ROLE_NAME = "lc-company-country";
    private static final String COUNTRY_CLIENT_ID = "life-control-client";

    private final IdentityProvider identityProvider;

    public KeycloakGroupEventListener(IdentityProvider identityProvider) {
        this.identityProvider = identityProvider;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompanyCreated(CompanyCreatedEvent event) {
        var groupName = COMPANY_GROUP_PREFIX + sanitizeGroupName(event.getCompanyName());
        try {
            identityProvider.createGroupWithRole(
                    groupName,
                    Map.of("company_id", List.of(event.getId().toString())),
                    COMPANY_ROLE_NAME,
                    COMPANY_CLIENT_ID
            );
            logger.info("Keycloak group created for company: name={}, id={}", groupName, event.getId());
        } catch (IdentityProviderException e) {
            logger.warn("Failed to create Keycloak group for company: name={}, id={}, error={}",
                    groupName, event.getId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompanyCountryCreated(CompanyCountryCreatedEvent event) {
        var groupName = COUNTRY_GROUP_PREFIX + sanitizeGroupName(event.getCountryName());
        try {
            identityProvider.createGroupWithRole(
                    groupName,
                    Map.of("company_country_id", List.of(event.getCompanyCountryId().toString())),
                    COUNTRY_ROLE_NAME,
                    COUNTRY_CLIENT_ID
            );
            logger.info("Keycloak group created for company-country: name={}, id={}",
                    groupName, event.getCompanyCountryId());
        } catch (IdentityProviderException e) {
            logger.warn("Failed to create Keycloak group for company-country: name={}, id={}, error={}",
                    groupName, event.getCompanyCountryId(), e.getMessage());
        }
    }

    private static String sanitizeGroupName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
    }
}
