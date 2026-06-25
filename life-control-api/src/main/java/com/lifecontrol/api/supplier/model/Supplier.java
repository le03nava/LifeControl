package com.lifecontrol.api.supplier.model;

import com.lifecontrol.api.common.address.model.Address;
import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.country.model.Country;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "suppliers")
public class Supplier extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "supplier_name", nullable = false, unique = true)
    private String supplierName;

    @Column(name = "razon_social")
    private String razonSocial;

    @Column(nullable = false, unique = true)
    private String rfc;

    private String email;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "internal_number")
    private String internalNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id")
    private Country country;

    @OneToOne(cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    @JoinColumn(name = "address_id")
    private Address address;

    private String street;

    @Column(name = "street_number")
    private String streetNumber;

    private String neighborhood;

    @Column(name = "zip_code")
    private String zipCode;

    private String city;

    private String state;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Supplier() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public String getRazonSocial() {
        return razonSocial;
    }

    public String getRfc() {
        return rfc;
    }

    public String getEmail() {
        return email;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getInternalNumber() {
        return internalNumber;
    }

    public Country getCountry() {
        return country;
    }

    public Address getAddress() {
        return address;
    }

    public String getStreet() {
        return street;
    }

    public String getStreetNumber() {
        return streetNumber;
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

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setSupplierName(String supplierName) {
        this.supplierName = supplierName;
    }

    public void setRazonSocial(String razonSocial) {
        this.razonSocial = razonSocial;
    }

    public void setRfc(String rfc) {
        this.rfc = rfc;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public void setInternalNumber(String internalNumber) {
        this.internalNumber = internalNumber;
    }

    public void setCountry(Country country) {
        this.country = country;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public void setStreet(String street) {
        this.street = street;
    }

    public void setStreetNumber(String streetNumber) {
        this.streetNumber = streetNumber;
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

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Supplier supplier = new Supplier();

        public Builder id(UUID id) {
            supplier.id = id;
            return this;
        }

        public Builder supplierName(String supplierName) {
            supplier.supplierName = supplierName;
            return this;
        }

        public Builder razonSocial(String razonSocial) {
            supplier.razonSocial = razonSocial;
            return this;
        }

        public Builder rfc(String rfc) {
            supplier.rfc = rfc;
            return this;
        }

        public Builder email(String email) {
            supplier.email = email;
            return this;
        }

        public Builder phoneNumber(String phoneNumber) {
            supplier.phoneNumber = phoneNumber;
            return this;
        }

        public Builder internalNumber(String internalNumber) {
            supplier.internalNumber = internalNumber;
            return this;
        }

        public Builder country(Country country) {
            supplier.country = country;
            return this;
        }

        public Builder address(Address address) {
            supplier.address = address;
            return this;
        }

        public Builder street(String street) {
            supplier.street = street;
            return this;
        }

        public Builder streetNumber(String streetNumber) {
            supplier.streetNumber = streetNumber;
            return this;
        }

        public Builder neighborhood(String neighborhood) {
            supplier.neighborhood = neighborhood;
            return this;
        }

        public Builder zipCode(String zipCode) {
            supplier.zipCode = zipCode;
            return this;
        }

        public Builder city(String city) {
            supplier.city = city;
            return this;
        }

        public Builder state(String state) {
            supplier.state = state;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            supplier.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            supplier.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            supplier.setUpdatedAt(updatedAt);
            return this;
        }

        public Supplier build() {
            return supplier;
        }
    }
}
