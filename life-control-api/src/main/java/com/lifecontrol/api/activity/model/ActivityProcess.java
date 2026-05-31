package com.lifecontrol.api.activity.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Seeded reference table for business processes (e.g. COMPANY, ORDER, INVENTORY).
 * Each activity log entry references a process that identifies the originating domain.
 */
@Entity
@Table(name = "activity_processes")
public class ActivityProcess {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Default constructor for JPA
    protected ActivityProcess() {}

    // ── Getters ──────────────────────────────────────────────

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // ── Builder ──────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ActivityProcess process = new ActivityProcess();

        public Builder id(UUID id) {
            process.id = id;
            return this;
        }

        public Builder name(String name) {
            process.name = name;
            return this;
        }

        public Builder description(String description) {
            process.description = description;
            return this;
        }

        public ActivityProcess build() {
            return process;
        }
    }
}
