package com.lifecontrol.api.company.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event published after a company has been successfully persisted.
 * Carries the company's internal UUID, business companyId, and name.
 */
public class CompanyCreatedEvent extends ApplicationEvent {

    private final UUID id;
    private final Integer companyId;
    private final String companyName;

    public CompanyCreatedEvent(Object source, UUID id, Integer companyId, String companyName) {
        super(source);
        this.id = id;
        this.companyId = companyId;
        this.companyName = companyName;
    }

    public UUID getId() {
        return id;
    }

    public Integer getCompanyId() {
        return companyId;
    }

    public String getCompanyName() {
        return companyName;
    }
}
