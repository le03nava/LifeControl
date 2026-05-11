package com.lifecontrol.api.company.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.country.model.Country;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "company_countries",
       uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "country_id"}))
public class CompanyCountry extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "local_alias", length = 200)
    private String localAlias;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    // Default constructor for JPA
    public CompanyCountry() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getLocalAlias() {
        return localAlias;
    }

    public Company getCompany() {
        return company;
    }

    public Country getCountry() {
        return country;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setLocalAlias(String localAlias) {
        this.localAlias = localAlias;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public void setCountry(Country country) {
        this.country = country;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final CompanyCountry companyCountry = new CompanyCountry();

        public Builder id(UUID id) {
            companyCountry.id = id;
            return this;
        }

        public Builder localAlias(String localAlias) {
            companyCountry.localAlias = localAlias;
            return this;
        }

        public Builder company(Company company) {
            companyCountry.company = company;
            return this;
        }

        public Builder country(Country country) {
            companyCountry.country = country;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            companyCountry.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            companyCountry.setUpdatedAt(updatedAt);
            return this;
        }

        public CompanyCountry build() {
            return companyCountry;
        }
    }
}
