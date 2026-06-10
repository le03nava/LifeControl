package com.lifecontrol.api.usersadmin.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_preferences")
public class UserPreferences extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "keycloak_user_id", nullable = false, unique = true, length = 36)
    private String keycloakUserId;

    @Column(name = "company_country_id")
    private UUID companyCountryId;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "company_region_id")
    private UUID companyRegionId;

    @Column(name = "company_zone_id")
    private UUID companyZoneId;

    @Column(name = "company_store_id")
    private UUID companyStoreId;

    // Default constructor for JPA
    public UserPreferences() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getKeycloakUserId() {
        return keycloakUserId;
    }

    public UUID getCompanyCountryId() {
        return companyCountryId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getCompanyRegionId() {
        return companyRegionId;
    }

    public UUID getCompanyZoneId() {
        return companyZoneId;
    }

    public UUID getCompanyStoreId() {
        return companyStoreId;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setKeycloakUserId(String keycloakUserId) {
        this.keycloakUserId = keycloakUserId;
    }

    public void setCompanyCountryId(UUID companyCountryId) {
        this.companyCountryId = companyCountryId;
    }

    public void setCompanyId(UUID companyId) {
        this.companyId = companyId;
    }

    public void setCompanyRegionId(UUID companyRegionId) {
        this.companyRegionId = companyRegionId;
    }

    public void setCompanyZoneId(UUID companyZoneId) {
        this.companyZoneId = companyZoneId;
    }

    public void setCompanyStoreId(UUID companyStoreId) {
        this.companyStoreId = companyStoreId;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final UserPreferences userPreferences = new UserPreferences();

        public Builder id(UUID id) {
            userPreferences.id = id;
            return this;
        }

        public Builder keycloakUserId(String keycloakUserId) {
            userPreferences.keycloakUserId = keycloakUserId;
            return this;
        }

        public Builder companyCountryId(UUID companyCountryId) {
            userPreferences.companyCountryId = companyCountryId;
            return this;
        }

        public Builder companyId(UUID companyId) {
            userPreferences.companyId = companyId;
            return this;
        }

        public Builder companyRegionId(UUID companyRegionId) {
            userPreferences.companyRegionId = companyRegionId;
            return this;
        }

        public Builder companyZoneId(UUID companyZoneId) {
            userPreferences.companyZoneId = companyZoneId;
            return this;
        }

        public Builder companyStoreId(UUID companyStoreId) {
            userPreferences.companyStoreId = companyStoreId;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            userPreferences.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            userPreferences.setUpdatedAt(updatedAt);
            return this;
        }

        public UserPreferences build() {
            return userPreferences;
        }
    }
}
