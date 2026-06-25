package com.lifecontrol.api.common.address.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.country.model.Country;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "addresses")
public class Address extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "street", length = 255)
    private String street;

    @Column(name = "street_number", length = 20)
    private String streetNumber;

    @Column(name = "internal_number", length = 20)
    private String internalNumber;

    @Column(name = "neighborhood", length = 255)
    private String neighborhood;

    @Column(name = "zip_code", length = 20)
    private String zipCode;

    @Column(name = "city", length = 255)
    private String city;

    @Column(name = "state", length = 255)
    private String state;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id")
    private Country country;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Address() {}

    // Getters
    public UUID getId() {
        return id;
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

    public Country getCountry() {
        return country;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
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

    public void setCountry(Country country) {
        this.country = country;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Address address = new Address();

        public Builder id(UUID id) {
            address.id = id;
            return this;
        }

        public Builder street(String street) {
            address.street = street;
            return this;
        }

        public Builder streetNumber(String streetNumber) {
            address.streetNumber = streetNumber;
            return this;
        }

        public Builder internalNumber(String internalNumber) {
            address.internalNumber = internalNumber;
            return this;
        }

        public Builder neighborhood(String neighborhood) {
            address.neighborhood = neighborhood;
            return this;
        }

        public Builder zipCode(String zipCode) {
            address.zipCode = zipCode;
            return this;
        }

        public Builder city(String city) {
            address.city = city;
            return this;
        }

        public Builder state(String state) {
            address.state = state;
            return this;
        }

        public Builder country(Country country) {
            address.country = country;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            address.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            address.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            address.setUpdatedAt(updatedAt);
            return this;
        }

        public Address build() {
            return address;
        }
    }
}
