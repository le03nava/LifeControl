package com.lifecontrol.api.store.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event published after a store has been successfully created.
 * Carries the company-store ID, company ID, the store name, and the parent zone name.
 */
public class CompanyStoreCreatedEvent extends ApplicationEvent {

    private final UUID companyStoreId;
    private final UUID companyId;
    private final String storeName;
    private final String zoneName;

    public CompanyStoreCreatedEvent(Object source, UUID companyStoreId,
                                    UUID companyId, String storeName, String zoneName) {
        super(source);
        this.companyStoreId = companyStoreId;
        this.companyId = companyId;
        this.storeName = storeName;
        this.zoneName = zoneName;
    }

    public UUID getCompanyStoreId() {
        return companyStoreId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getStoreName() {
        return storeName;
    }

    public String getZoneName() {
        return zoneName;
    }
}
