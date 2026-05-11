package com.lifecontrol.api.country.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "countries")
public class Country extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 2, nullable = false, unique = true)
    private String countryCode;

    @Column(length = 100, nullable = false)
    private String countryName;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Country() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public String getCountryName() {
        return countryName;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    public void setCountryName(String countryName) {
        this.countryName = countryName;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Country country = new Country();

        public Builder id(UUID id) {
            country.id = id;
            return this;
        }

        public Builder countryCode(String countryCode) {
            country.countryCode = countryCode;
            return this;
        }

        public Builder countryName(String countryName) {
            country.countryName = countryName;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            country.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            country.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            country.setUpdatedAt(updatedAt);
            return this;
        }

        public Country build() {
            return country;
        }
    }
}
