package com.lifecontrol.api.company.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event published after a region has been successfully associated with a company-country.
 * Carries the company-region ID, company ID, and the region name.
 */
public class CompanyRegionCreatedEvent extends ApplicationEvent {

    private final UUID companyRegionId;
    private final UUID companyId;
    private final String regionName;

    public CompanyRegionCreatedEvent(Object source, UUID companyRegionId,
                                      UUID companyId, String regionName) {
        super(source);
        this.companyRegionId = companyRegionId;
        this.companyId = companyId;
        this.regionName = regionName;
    }

    public UUID getCompanyRegionId() {
        return companyRegionId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getRegionName() {
        return regionName;
    }
}
