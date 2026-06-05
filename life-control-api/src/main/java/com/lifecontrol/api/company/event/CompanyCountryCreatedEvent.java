package com.lifecontrol.api.company.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event published after a country has been successfully associated with a company.
 * Carries the company-country ID, company ID, and the country name.
 */
public class CompanyCountryCreatedEvent extends ApplicationEvent {

    private final UUID companyCountryId;
    private final UUID companyId;
    private final String countryName;

    public CompanyCountryCreatedEvent(Object source, UUID companyCountryId,
                                       UUID companyId, String countryName) {
        super(source);
        this.companyCountryId = companyCountryId;
        this.companyId = companyId;
        this.countryName = countryName;
    }

    public UUID getCompanyCountryId() {
        return companyCountryId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getCountryName() {
        return countryName;
    }
}
