package com.lifecontrol.api.status.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "statuses",
       uniqueConstraints = @UniqueConstraint(columnNames = {"status_type_id", "status_name"}))
public class Status extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "status_name", length = 100, nullable = false)
    private String statusName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "status_type_id", nullable = false)
    private StatusType statusType;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Status() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public String getStatusName() {
        return statusName;
    }

    public StatusType getStatusType() {
        return statusType;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setStatusName(String statusName) {
        this.statusName = statusName;
    }

    public void setStatusType(StatusType statusType) {
        this.statusType = statusType;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Status status = new Status();

        public Builder id(UUID id) {
            status.id = id;
            return this;
        }

        public Builder statusName(String statusName) {
            status.statusName = statusName;
            return this;
        }

        public Builder statusType(StatusType statusType) {
            status.statusType = statusType;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            status.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            status.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            status.setUpdatedAt(updatedAt);
            return this;
        }

        public Status build() {
            return status;
        }
    }
}
