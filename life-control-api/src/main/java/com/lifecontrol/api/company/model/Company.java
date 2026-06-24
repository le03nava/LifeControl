package com.lifecontrol.api.company.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "companies")
public class Company extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_key", nullable = false, unique = true)
    private String companyKey;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "tipo_persona_id")
    private Integer tipoPersonaId;

    @Column(name = "razon_social")
    private String razonSocial;

    @Column(nullable = false, unique = true)
    private String rfc;

    private String phone;

    private String email;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "street")
    private String street;

    @Column(name = "street_number")
    private String streetNumber;

    @Column(name = "internal_number")
    private String internalNumber;

    @Column(name = "neighborhood")
    private String neighborhood;

    @Column(name = "zip_code")
    private String zipCode;

    @Column(name = "city")
    private String city;

    @Column(name = "state")
    private String state;

    @Column(name = "country_id")
    private UUID countryId;

    // Default constructor for JPA
    public Company() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getCompanyKey() {
        return companyKey;
    }

    public String getCompanyName() {
        return companyName;
    }

    public Integer getTipoPersonaId() {
        return tipoPersonaId;
    }

    public String getRazonSocial() {
        return razonSocial;
    }

    public String getRfc() {
        return rfc;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public String getStreet() {
        return street;
    }

    public String getStreetNumber() {
        return streetNumber;
    }

    public String getInternalNumber() {
        return internalNumber;
    }

    public String getNeighborhood() {
        return neighborhood;
    }

    public String getZipCode() {
        return zipCode;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public UUID getCountryId() {
        return countryId;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setCompanyKey(String companyKey) {
        this.companyKey = companyKey;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public void setTipoPersonaId(Integer tipoPersonaId) {
        this.tipoPersonaId = tipoPersonaId;
    }

    public void setRazonSocial(String razonSocial) {
        this.razonSocial = razonSocial;
    }

    public void setRfc(String rfc) {
        this.rfc = rfc;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public void setStreet(String street) {
        this.street = street;
    }

    public void setStreetNumber(String streetNumber) {
        this.streetNumber = streetNumber;
    }

    public void setInternalNumber(String internalNumber) {
        this.internalNumber = internalNumber;
    }

    public void setNeighborhood(String neighborhood) {
        this.neighborhood = neighborhood;
    }

    public void setZipCode(String zipCode) {
        this.zipCode = zipCode;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public void setState(String state) {
        this.state = state;
    }

    public void setCountryId(UUID countryId) {
        this.countryId = countryId;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Company company = new Company();

        public Builder id(UUID id) {
            company.id = id;
            return this;
        }

        public Builder companyKey(String companyKey) {
            company.companyKey = companyKey;
            return this;
        }

        public Builder companyName(String companyName) {
            company.companyName = companyName;
            return this;
        }

        public Builder tipoPersonaId(Integer tipoPersonaId) {
            company.tipoPersonaId = tipoPersonaId;
            return this;
        }

        public Builder razonSocial(String razonSocial) {
            company.razonSocial = razonSocial;
            return this;
        }

        public Builder rfc(String rfc) {
            company.rfc = rfc;
            return this;
        }

        public Builder phone(String phone) {
            company.phone = phone;
            return this;
        }

        public Builder email(String email) {
            company.email = email;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            company.enabled = enabled;
            return this;
        }

        public Builder street(String street) {
            company.street = street;
            return this;
        }

        public Builder streetNumber(String streetNumber) {
            company.streetNumber = streetNumber;
            return this;
        }

        public Builder internalNumber(String internalNumber) {
            company.internalNumber = internalNumber;
            return this;
        }

        public Builder neighborhood(String neighborhood) {
            company.neighborhood = neighborhood;
            return this;
        }

        public Builder zipCode(String zipCode) {
            company.zipCode = zipCode;
            return this;
        }

        public Builder city(String city) {
            company.city = city;
            return this;
        }

        public Builder state(String state) {
            company.state = state;
            return this;
        }

        public Builder countryId(UUID countryId) {
            company.countryId = countryId;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            company.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            company.setUpdatedAt(updatedAt);
            return this;
        }

        public Company build() {
            return company;
        }
    }
}