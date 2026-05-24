package com.lifecontrol.api.company.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "company_regions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"company_country_id", "region_code"}))
public class CompanyRegion extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_country_id", nullable = false)
    private CompanyCountry companyCountry;

    @Column(name = "region_code", length = 10, nullable = false)
    private String regionCode;

    @Column(name = "region_name", length = 100, nullable = false)
    private String regionName;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public CompanyRegion() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public CompanyCountry getCompanyCountry() {
        return companyCountry;
    }

    public String getRegionCode() {
        return regionCode;
    }

    public String getRegionName() {
        return regionName;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setCompanyCountry(CompanyCountry companyCountry) {
        this.companyCountry = companyCountry;
    }

    public void setRegionCode(String regionCode) {
        this.regionCode = regionCode;
    }

    public void setRegionName(String regionName) {
        this.regionName = regionName;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final CompanyRegion region = new CompanyRegion();

        public Builder id(UUID id) {
            region.id = id;
            return this;
        }

        public Builder companyCountry(CompanyCountry companyCountry) {
            region.companyCountry = companyCountry;
            return this;
        }

        public Builder regionCode(String regionCode) {
            region.regionCode = regionCode;
            return this;
        }

        public Builder regionName(String regionName) {
            region.regionName = regionName;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            region.enabled = enabled;
            return this;
        }

        public CompanyRegion build() {
            return region;
        }
    }
}
