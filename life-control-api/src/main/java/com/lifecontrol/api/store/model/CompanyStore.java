package com.lifecontrol.api.store.model;

import com.lifecontrol.api.common.address.model.Address;
import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.company.model.Company;
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

    /**
     * The company this store belongs to. <b>The database derives and owns this column</b>: {@code V50}'s
     * {@code trg_company_stores_derive_company} trigger fills it from the parent's stored owner when a row
     * arrives without one, which is why the mapping is {@code insertable = false, updatable = false} and
     * why the application never writes it — there is no request field, no setter and no builder method
     * for it.
     *
     * <p>It exists because {@code V49} makes the column {@code NOT NULL} and pairs it with
     * {@code fk_company_stores_zone_same_company}: the owner is stored so the database can refuse a store
     * whose company disagrees with its zone's, and so {@code purchase_orders} has a
     * {@code (company_id, id)} target to key its own composite foreign key against. Reading it back is
     * not what answers "who owns this row" on the API paths — {@code CompanyStoreOwnerResolver} still
     * walks the tree — so the two can only agree.</p>
     *
     * <p>The derivation is <b>not</b> a JPA callback on purpose, and this is the class that measured why: a
     * {@code @PrePersist} that read {@code companyZone.getCompany()} forced the caller to load the zone's
     * own associations, and saving a store while holding a <em>detached</em> zone — legitimate JPA, since
     * the child only needs the parent's identity — threw {@code LazyInitializationException}. That is how
     * {@code PurchaseOrderDetailVariantIntegrationTest} failed.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false, insertable = false, updatable = false)
    private Company company;

    @Column(name = "store_name", nullable = false)
    private String storeName;

    @Column(name = "email")
    private String email;

    @Column(name = "phone_number")
    private String phoneNumber;

    @OneToOne(
            cascade = {CascadeType.PERSIST, CascadeType.MERGE},
            fetch = FetchType.LAZY)
    @JoinColumn(name = "address_id")
    private Address address;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // Default constructor for JPA
    public CompanyStore() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public Company getCompany() {
        return company;
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

    public Address getAddress() {
        return address;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public long getVersion() {
        return version;
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

    public void setAddress(Address address) {
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

        // No company(...) on purpose: the column has a single author — V50's trigger, which derives it
        // from the zone. A builder method would be one more way to write a company that disagrees.

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

        public Builder address(Address address) {
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
