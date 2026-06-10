package com.lifecontrol.api.customer.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "rfc")
    private String rfc;

    @Column(name = "sales_channel", nullable = false)
    private String salesChannel;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Customer() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getRfc() {
        return rfc;
    }

    public String getSalesChannel() {
        return salesChannel;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public void setRfc(String rfc) {
        this.rfc = rfc;
    }

    public void setSalesChannel(String salesChannel) {
        this.salesChannel = salesChannel;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Customer customer = new Customer();

        public Builder id(UUID id) {
            customer.id = id;
            return this;
        }

        public Builder name(String name) {
            customer.name = name;
            return this;
        }

        public Builder email(String email) {
            customer.email = email;
            return this;
        }

        public Builder phone(String phone) {
            customer.phone = phone;
            return this;
        }

        public Builder rfc(String rfc) {
            customer.rfc = rfc;
            return this;
        }

        public Builder salesChannel(String salesChannel) {
            customer.salesChannel = salesChannel;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            customer.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            customer.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            customer.setUpdatedAt(updatedAt);
            return this;
        }

        public Customer build() {
            return customer;
        }
    }
}
