package com.lifecontrol.api.status.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "status_types")
public class StatusType extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "status_type_name", length = 100, nullable = false, unique = true)
    private String statusTypeName;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public StatusType() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getStatusTypeName() {
        return statusTypeName;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setStatusTypeName(String statusTypeName) {
        this.statusTypeName = statusTypeName;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final StatusType statusType = new StatusType();

        public Builder id(UUID id) {
            statusType.id = id;
            return this;
        }

        public Builder statusTypeName(String statusTypeName) {
            statusType.statusTypeName = statusTypeName;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            statusType.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            statusType.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            statusType.setUpdatedAt(updatedAt);
            return this;
        }

        public StatusType build() {
            return statusType;
        }
    }
}
