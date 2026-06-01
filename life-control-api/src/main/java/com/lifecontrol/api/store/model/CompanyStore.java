package com.lifecontrol.api.store.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.company.model.CompanyZone;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "company_stores")
public class CompanyStore extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_zone_id", nullable = false)
    private CompanyZone companyZone;

    @Column(name = "store_name", nullable = false)
    private String storeName;

    @Column(name = "email")
    private String email;

    @Column(name = "phone_number")
    private String phoneNumber;

    @OneToOne(cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    @JoinColumn(name = "address_id")
    private CompanyStoreAddress address;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public CompanyStore() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public CompanyZone getCompanyZone() {
        return companyZone;
    }

    public String getStoreName() {
        return storeName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public CompanyStoreAddress getAddress() {
        return address;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setCompanyZone(CompanyZone companyZone) {
        this.companyZone = companyZone;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public void setAddress(CompanyStoreAddress address) {
        this.address = address;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final CompanyStore store = new CompanyStore();

        public Builder id(UUID id) {
            store.id = id;
            return this;
        }

        public Builder companyZone(CompanyZone companyZone) {
            store.companyZone = companyZone;
            return this;
        }

        public Builder storeName(String storeName) {
            store.storeName = storeName;
            return this;
        }

        public Builder email(String email) {
            store.email = email;
            return this;
        }

        public Builder phoneNumber(String phoneNumber) {
            store.phoneNumber = phoneNumber;
            return this;
        }

        public Builder address(CompanyStoreAddress address) {
            store.address = address;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            store.enabled = enabled;
            return this;
        }

        public CompanyStore build() {
            return store;
        }
    }
}
