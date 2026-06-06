package com.lifecontrol.api.company.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event published after a zone has been successfully associated with a company-region.
 * Carries the company-zone ID, company ID, the zone name, and the parent region name.
 */
public class CompanyZoneCreatedEvent extends ApplicationEvent {

    private final UUID companyZoneId;
    private final UUID companyId;
    private final String zoneName;
    private final String regionName;

    public CompanyZoneCreatedEvent(Object source, UUID companyZoneId,
                                    UUID companyId, String zoneName, String regionName) {
        super(source);
        this.companyZoneId = companyZoneId;
        this.companyId = companyId;
        this.zoneName = zoneName;
        this.regionName = regionName;
    }

    public UUID getCompanyZoneId() {
        return companyZoneId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getZoneName() {
        return zoneName;
    }

    public String getRegionName() {
        return regionName;
    }
}
