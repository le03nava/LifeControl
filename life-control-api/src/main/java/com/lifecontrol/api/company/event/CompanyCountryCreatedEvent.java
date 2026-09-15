package com.lifecontrol.api.company.event;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Event published after a country has been successfully associated with a company.
 * Carries the company-country ID, company ID, the country name, and the parent company name.
 */
public class CompanyCountryCreatedEvent extends ApplicationEvent {

    private final UUID companyCountryId;
    private final UUID companyId;
    private final String countryName;
    private final String companyName;

    public CompanyCountryCreatedEvent(
            Object source, UUID companyCountryId, UUID companyId, String countryName, String companyName) {
        super(source);
        this.companyCountryId = companyCountryId;
        this.companyId = companyId;
        this.countryName = countryName;
        this.companyName = companyName;
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

    public String getCompanyName() {
        return companyName;
    }
}
