package com.lifecontrol.api.store.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event published after a store has been successfully created.
 * Carries the company-store ID, company ID, and the store name.
 */
public class CompanyStoreCreatedEvent extends ApplicationEvent {

    private final UUID companyStoreId;
    private final UUID companyId;
    private final String storeName;

    public CompanyStoreCreatedEvent(Object source, UUID companyStoreId,
                                    UUID companyId, String storeName) {
        super(source);
        this.companyStoreId = companyStoreId;
        this.companyId = companyId;
        this.storeName = storeName;
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
}
