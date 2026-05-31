package com.lifecontrol.api.company.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "company_zones",
       uniqueConstraints = @UniqueConstraint(columnNames = {"company_region_id", "zone_code"}))
public class CompanyZone extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_region_id", nullable = false)
    private CompanyRegion companyRegion;

    @Column(name = "zone_code", length = 10, nullable = false)
    private String zoneCode;

    @Column(name = "zone_name", length = 100, nullable = false)
    private String zoneName;

    @Column(length = 255)
    private String description;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public CompanyZone() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public CompanyRegion getCompanyRegion() {
        return companyRegion;
    }

    public String getZoneCode() {
        return zoneCode;
    }

    public String getZoneName() {
        return zoneName;
    }

    public String getDescription() {
        return description;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setCompanyRegion(CompanyRegion companyRegion) {
        this.companyRegion = companyRegion;
    }

    public void setZoneCode(String zoneCode) {
        this.zoneCode = zoneCode;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final CompanyZone zone = new CompanyZone();

        public Builder id(UUID id) {
            zone.id = id;
            return this;
        }

        public Builder companyRegion(CompanyRegion companyRegion) {
            zone.companyRegion = companyRegion;
            return this;
        }

        public Builder zoneCode(String zoneCode) {
            zone.zoneCode = zoneCode;
            return this;
        }

        public Builder zoneName(String zoneName) {
            zone.zoneName = zoneName;
            return this;
        }

        public Builder description(String description) {
            zone.description = description;
            return this;
        }

        public Builder displayOrder(Integer displayOrder) {
            zone.displayOrder = displayOrder;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            zone.enabled = enabled;
            return this;
        }

        public CompanyZone build() {
            return zone;
        }
    }
}
