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
     * The company this zone belongs to. <b>The database derives and owns this column</b>: {@code V50}'s
     * {@code trg_company_zones_derive_company} trigger fills it from the parent's stored owner when a row
     * arrives without one, which is why the mapping is {@code insertable = false, updatable = false} and
     * why the application never writes it — there is no request field, no setter and no builder method
     * for it.
     *
     * <p>It exists because {@code V47} makes the column {@code NOT NULL} and pairs it with
     * {@code fk_company_zones_region_same_company}: the owner is stored so the database can refuse a zone
     * whose company disagrees with its region's, and so {@code company_stores} has a
     * {@code (company_id, id)} target to key its own composite foreign key against. Reading it back is not
     * what answers "who owns this row" on the API paths — {@code CompanyStoreOwnerResolver} still walks
     * the tree — so the two can only agree.</p>
     *
     * <p>The derivation is <b>not</b> a JPA callback on purpose: a {@code @PrePersist} that read
     * {@code companyRegion.getCompany()} forced the caller to load the parent's own associations, and
     * saving a child that names a detached parent is legitimate JPA (measured: it broke
     * {@code PurchaseOrderDetailVariantIntegrationTest} with a {@code LazyInitializationException}).</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false, insertable = false, updatable = false)
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

        // No company(...) on purpose: the column has a single author — V50's trigger, which derives it
        // from the region. A builder method would be one more way to write a company that disagrees.

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
