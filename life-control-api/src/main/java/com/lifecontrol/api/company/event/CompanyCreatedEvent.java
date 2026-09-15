package com.lifecontrol.api.company.event;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Event published after a company has been successfully persisted.
 * Carries the company's internal UUID, business companyKey, and name.
 */
public class CompanyCreatedEvent extends ApplicationEvent {

    private final UUID id;
    private final String companyKey;
    private final String companyName;

    public CompanyCreatedEvent(Object source, UUID id, String companyKey, String companyName) {
        super(source);
        this.id = id;
        this.companyKey = companyKey;
        this.companyName = companyName;
    }

    public UUID getId() {
        return id;
    }

    public String getCompanyKey() {
        return companyKey;
    }

    public String getCompanyName() {
        return companyName;
    }
}
