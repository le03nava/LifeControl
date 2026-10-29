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
     * The company this region belongs to, derived from {@link #companyCountry} at persist time and
     * never supplied by a caller — there is no request field and no setter for it.
     *
     * <p>It exists because {@code V45} makes the column {@code NOT NULL} and pairs it with
     * {@code fk_company_regions_country_same_company}: the owner is stored so the database can refuse a
     * region whose company disagrees with its country's, and so {@code company_zones} has a
     * {@code (company_id, id)} target to key its own composite foreign key against. Reading it back is
     * not what answers "who owns this row" on the API paths — {@code CompanyStoreOwnerResolver} still
     * walks the tree — so the two can only agree.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "region_code", length = 10, nullable = false)
    private String regionCode;

    @Column(name = "region_name", length = 100, nullable = false)
    private String regionName;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public CompanyRegion() {}

    /**
     * Derives {@link #company} from {@link #companyCountry} at persist time, so the {@code NOT NULL}
     * column {@code V45} lands is never written as an explicit {@code NULL}.
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
    protected void deriveCompanyFromCountry() {
        if (company == null && companyCountry != null) {
            company = companyCountry.getCompany();
        }
    }

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

        // No company(...) on purpose: the owner is derived by deriveCompanyFromCountry(). A builder
        // method would be one more way to write a company that disagrees with the country.

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
