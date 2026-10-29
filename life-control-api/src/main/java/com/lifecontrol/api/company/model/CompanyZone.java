package com.lifecontrol.api.company.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "company_zones", uniqueConstraints = @UniqueConstraint(columnNames = {"company_region_id", "zone_code"}))
public class CompanyZone extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_region_id", nullable = false)
    private CompanyRegion companyRegion;

    /**
     * The company this zone belongs to, derived from {@link #companyRegion} at persist time and never
     * supplied by a caller — there is no request field and no setter for it.
     *
     * <p>It exists because {@code V47} makes the column {@code NOT NULL} and pairs it with
     * {@code fk_company_zones_region_same_company}: the owner is stored so the database can refuse a zone
     * whose company disagrees with its region's, and so {@code company_stores} has a
     * {@code (company_id, id)} target to key its own composite foreign key against. Reading it back is not
     * what answers "who owns this row" on the API paths — {@code CompanyStoreOwnerResolver} still walks
     * the tree — so the two can only agree.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

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

    /**
     * Derives {@link #company} from {@link #companyRegion} at persist time, so the {@code NOT NULL}
     * column {@code V47} lands is never written as an explicit {@code NULL}.
     *
     * <p>This method <strong>must not</strong> be named {@code onCreate}: {@link Auditable} already
     * declares {@code @PrePersist protected void onCreate()}, so a method with that same signature here
     * would <em>override</em> it and {@code Auditable}'s body would never run, leaving
     * {@code created_at} / {@code updated_at} unset and failing their NOT NULL columns. A distinct name
     * keeps both callbacks active.</p>
     *
     * <p>The set-if-null guard means the mapping never overwrites a value; nothing in this codebase
     * supplies one, and the composite key is what refuses a caller that ever did.</p>
     */
    @PrePersist
    protected void deriveCompanyFromRegion() {
        if (company == null && companyRegion != null) {
            company = companyRegion.getCompany();
        }
    }

    // Getters
    public UUID getId() {
        return id;
    }

    public Company getCompany() {
        return company;
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

        // No company(...) on purpose: the owner is derived by deriveCompanyFromRegion(). A builder
        // method would be one more way to write a company that disagrees with the region.

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
