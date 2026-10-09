package com.lifecontrol.api.provisioning.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One role of the diff a human was asked to approve, mapped to the
 * {@code access_provisioning_reviewed_roles} table created by
 * {@code V25__employee_access_provisioning_reviewed_roles.sql}.
 *
 * <p>This is the <b>frozen reviewed set</b> (decision T71): one row per role the diff touches, with
 * its {@link #direction}, written in the same transaction as the gated
 * {@link AccessProvisioningTask} so the approver and the apply read the same value. The
 * {@code (task_id, role_name)} UNIQUE is {@code uq_access_provisioning_reviewed_roles}, so the same
 * role cannot be frozen twice for one task.</p>
 *
 * <p>It is an <b>immutable log line</b> and therefore has getters and a builder but <b>no setters</b>,
 * mirroring {@code activity.model.ActivityLog}'s immutable style. The row is written once and the
 * whole set is replaced when the task returns to the gate (decision T72), which is why it carries
 * only {@link #createdAt} and does <b>not</b> extend {@code Auditable}: the table has no
 * {@code updated_at} column on purpose, because an {@code updated_at} would suggest a mutation that
 * must never happen. The parent association is the {@code @ManyToOne} side of the pair, mirroring
 * {@link AccessProvisioningAppliedRole#getTask()}.</p>
 *
 * <p>{@link #direction} is mapped like {@code kind} and {@code status} on the parent: a plain
 * {@code VARCHAR(10)} with {@code @Enumerated(EnumType.STRING)} and no CHECK, so the schema keeps its
 * "zero {@code CHECK (col IN ...)}" stance (V22) and the value set stays the Java enum's
 * ({@link AccessProvisioningRoleDirection}).</p>
 */
@Entity
@Table(name = "access_provisioning_reviewed_roles")
public class AccessProvisioningReviewedRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private AccessProvisioningTask task;

    @Column(name = "role_name", length = 100, nullable = false)
    private String roleName;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 10, nullable = false)
    private AccessProvisioningRoleDirection direction;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Default constructor for JPA
    public AccessProvisioningReviewedRole() {}

    /**
     * Fills {@link #createdAt} at persist time so the {@code created_at} NOT NULL column is never
     * written as an explicit {@code NULL}. The set-if-null guard means an explicitly supplied value
     * still wins.
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // Getters (no setters — immutable log line)
    public UUID getId() {
        return id;
    }

    public AccessProvisioningTask getTask() {
        return task;
    }

    public String getRoleName() {
        return roleName;
    }

    public AccessProvisioningRoleDirection getDirection() {
        return direction;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final AccessProvisioningReviewedRole reviewedRole = new AccessProvisioningReviewedRole();

        public Builder id(UUID id) {
            reviewedRole.id = id;
            return this;
        }

        public Builder task(AccessProvisioningTask task) {
            reviewedRole.task = task;
            return this;
        }

        public Builder roleName(String roleName) {
            reviewedRole.roleName = roleName;
            return this;
        }

        public Builder direction(AccessProvisioningRoleDirection direction) {
            reviewedRole.direction = direction;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            reviewedRole.createdAt = createdAt;
            return this;
        }

        public AccessProvisioningReviewedRole build() {
            return reviewedRole;
        }
    }
}
