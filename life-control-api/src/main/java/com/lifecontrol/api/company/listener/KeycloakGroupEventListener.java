package com.lifecontrol.api.company.listener;

import com.lifecontrol.api.company.event.CompanyCountryCreatedEvent;
import com.lifecontrol.api.company.event.CompanyCreatedEvent;
import com.lifecontrol.api.company.event.CompanyRegionCreatedEvent;
import com.lifecontrol.api.company.event.CompanyZoneCreatedEvent;
import com.lifecontrol.api.store.event.CompanyStoreCreatedEvent;
import com.lifecontrol.api.usersadmin.identity.IdentityProvider;
import com.lifecontrol.api.usersadmin.identity.IdentityProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Listens for {@link CompanyCreatedEvent} and creates a corresponding group in
 * Keycloak. Fires only after the company transaction commits successfully.
 * Group creation failures are logged but never propagated.
 */
@Component
public class KeycloakGroupEventListener {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakGroupEventListener.class);

    private static final String GROUP_PREFIX_COMPANY = "lc-company-";
    private static final String GROUP_PREFIX_COUNTRY = "lc-company-country-";
    private static final String GROUP_PREFIX_REGION = "lc-company-region-";
    private static final String GROUP_PREFIX_ZONE = "lc-company-zone-";
    private static final String GROUP_PREFIX_STORE = "lc-company-store-";

    private final IdentityProvider identityProvider;

    public KeycloakGroupEventListener(IdentityProvider identityProvider) {
        this.identityProvider = identityProvider;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompanyCreated(CompanyCreatedEvent event) {
        var groupName = GROUP_PREFIX_COMPANY + sanitizeGroupName(event.getCompanyName());
        try {
            identityProvider.createGroup(
                    groupName,
                    Map.of("company_id", List.of(event.getId().toString())),
                    Optional.empty()
            );
            logger.info("Keycloak group created for company: name={}, id={}", groupName, event.getId());
        } catch (IdentityProviderException e) {
            logger.warn("Failed to create Keycloak group for company: name={}, id={}, error={}",
                    groupName, event.getId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompanyCountryCreated(CompanyCountryCreatedEvent event) {
        var groupName = GROUP_PREFIX_COUNTRY + sanitizeGroupName(event.getCountryName());
        var parentGroupName = GROUP_PREFIX_COMPANY + sanitizeGroupName(event.getCompanyName());
        try {
            identityProvider.createGroup(
                    groupName,
                    Map.of("company_country_id", List.of(event.getCompanyCountryId().toString())),
                    resolveParentGroupId(parentGroupName)
            );
            logger.info("Keycloak group created for company-country: name={}, id={}",
                    groupName, event.getCompanyCountryId());
        } catch (IdentityProviderException e) {
            logger.warn("Failed to create Keycloak group for company-country: name={}, id={}, error={}",
                    groupName, event.getCompanyCountryId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompanyRegionCreated(CompanyRegionCreatedEvent event) {
        var groupName = GROUP_PREFIX_REGION + sanitizeGroupName(event.getRegionName());
        var parentGroupName = GROUP_PREFIX_COUNTRY + sanitizeGroupName(event.getCountryName());
        try {
            identityProvider.createGroup(
                    groupName,
                    Map.of("company_region_id", List.of(event.getCompanyRegionId().toString())),
                    resolveParentGroupId(parentGroupName)
            );
            logger.info("Keycloak group created for company-region: name={}, id={}",
                    groupName, event.getCompanyRegionId());
        } catch (IdentityProviderException e) {
            logger.warn("Failed to create Keycloak group for company-region: name={}, id={}, error={}",
                    groupName, event.getCompanyRegionId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompanyZoneCreated(CompanyZoneCreatedEvent event) {
        var groupName = GROUP_PREFIX_ZONE + sanitizeGroupName(event.getZoneName());
        var parentGroupName = GROUP_PREFIX_REGION + sanitizeGroupName(event.getRegionName());
        try {
            identityProvider.createGroup(
                    groupName,
                    Map.of("company_zone_id", List.of(event.getCompanyZoneId().toString())),
                    resolveParentGroupId(parentGroupName)
            );
            logger.info("Keycloak group created for company-zone: name={}, id={}",
                    groupName, event.getCompanyZoneId());
        } catch (IdentityProviderException e) {
            logger.warn("Failed to create Keycloak group for company-zone: name={}, id={}, error={}",
                    groupName, event.getCompanyZoneId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompanyStoreCreated(CompanyStoreCreatedEvent event) {
        var groupName = GROUP_PREFIX_STORE + sanitizeGroupName(event.getStoreName());
        var parentGroupName = GROUP_PREFIX_ZONE + sanitizeGroupName(event.getZoneName());
        try {
            identityProvider.createGroup(
                    groupName,
                    Map.of("company_store_id", List.of(event.getCompanyStoreId().toString())),
                    resolveParentGroupId(parentGroupName)
            );
            logger.info("Keycloak group created for company-store: name={}, id={}",
                    groupName, event.getCompanyStoreId());
        } catch (IdentityProviderException e) {
            logger.warn("Failed to create Keycloak group for company-store: name={}, id={}, error={}",
                    groupName, event.getCompanyStoreId(), e.getMessage());
        }
    }

    private Optional<String> resolveParentGroupId(String parentGroupName) {
        var parentId = identityProvider.findGroupIdByName(parentGroupName);
        if (parentId.isEmpty()) {
            logger.warn("Parent Keycloak group not found: name={}, creating group at top level",
                    parentGroupName);
        }
        return parentId;
    }

    private static String sanitizeGroupName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
    }
}
