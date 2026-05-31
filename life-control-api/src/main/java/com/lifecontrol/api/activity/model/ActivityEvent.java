package com.lifecontrol.api.activity.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Seeded reference table for CRUD events (CREATE, READ, UPDATE, DELETE).
 * Each activity log entry references an event that identifies the action performed.
 */
@Entity
@Table(name = "activity_events")
public class ActivityEvent {

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
    protected ActivityEvent() {}

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
        private final ActivityEvent event = new ActivityEvent();

        public Builder id(UUID id) {
            event.id = id;
            return this;
        }

        public Builder name(String name) {
            event.name = name;
            return this;
        }

        public Builder description(String description) {
            event.description = description;
            return this;
        }

        public ActivityEvent build() {
            return event;
        }
    }
}
