package com.lifecontrol.api.company.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(
        name = "company_regions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"company_country_id", "region_code"}))
public class CompanyRegion extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_country_id", nullable = false)
    private CompanyCountry companyCountry;

    /**
     * The company this region belongs to. <b>The database derives and owns this column</b>: {@code V50}'s
     * {@code trg_company_regions_derive_company} trigger fills it from the parent's stored owner when a
     * row arrives without one, which is why the mapping is {@code insertable = false, updatable = false}
     * and why the application never writes it — there is no request field, no setter and no builder
     * method for it.
     *
     * <p>It exists because {@code V45} makes the column {@code NOT NULL} and pairs it with
     * {@code fk_company_regions_country_same_company}: the owner is stored so the database can refuse a
     * region whose company disagrees with its country's, and so {@code company_zones} has a
     * {@code (company_id, id)} target to key its own composite foreign key against. Reading it back is
     * not what answers "who owns this row" on the API paths — {@code CompanyStoreOwnerResolver} still
     * walks the tree — so the two can only agree.</p>
     *
     * <p>The derivation is <b>not</b> a JPA callback on purpose: a {@code @PrePersist} that read
     * {@code companyCountry.getCompany()} forced the caller to load the parent's own associations, and
     * saving a child that names a detached parent is legitimate JPA (measured: it broke
     * {@code PurchaseOrderDetailVariantIntegrationTest} with a {@code LazyInitializationException}).</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false, insertable = false, updatable = false)
    private Company company;

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

    public Company getCompany() {
        return company;
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

        // No company(...) on purpose: the column has a single author — V50's trigger, which derives it
        // from the country. A builder method would be one more way to write a company that disagrees.

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
