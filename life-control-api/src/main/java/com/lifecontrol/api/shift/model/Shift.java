package com.lifecontrol.api.shift.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "shifts")
public class Shift extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_store_id", nullable = false)
    private UUID companyStoreId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Shift() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public UUID getCompanyStoreId() {
        return companyStoreId;
    }

    public String getUserId() {
        return userId;
    }

    public LocalDateTime getOpenedAt() {
        return openedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public String getStatus() {
        return status;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setCompanyStoreId(UUID companyStoreId) {
        this.companyStoreId = companyStoreId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setOpenedAt(LocalDateTime openedAt) {
        this.openedAt = openedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Shift shift = new Shift();

        public Builder id(UUID id) {
            shift.id = id;
            return this;
        }

        public Builder companyStoreId(UUID companyStoreId) {
            shift.companyStoreId = companyStoreId;
            return this;
        }

        public Builder userId(String userId) {
            shift.userId = userId;
            return this;
        }

        public Builder openedAt(LocalDateTime openedAt) {
            shift.openedAt = openedAt;
            return this;
        }

        public Builder closedAt(LocalDateTime closedAt) {
            shift.closedAt = closedAt;
            return this;
        }

        public Builder status(String status) {
            shift.status = status;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            shift.enabled = enabled;
            return this;
        }

        public Shift build() {
            return shift;
        }
    }
}
