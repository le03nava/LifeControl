package com.lifecontrol.api.provisioning.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One role the {@link AccessProvisioningTask} actually granted or removed, mapped to the
 * {@code access_provisioning_applied_roles} table created by
 * {@code V22__employee_access_provisioning.sql}.
 *
 * <p>This is the <b>applied snapshot</b> (record T8): immutable history, one row per role, and a
 * child table rather than a JSON column because the allowlist test (record T12) reads it as rows.
 * The {@code (task_id, role_name)} UNIQUE is {@code uq_access_provisioning_applied_roles}, so the
 * same role cannot be recorded twice for one task.</p>
 *
 * <p>It is an <b>immutable log line</b> and therefore has getters and a builder but <b>no setters</b>,
 * mirroring {@code activity.model.ActivityLog}'s immutable style. It carries only
 * {@link #createdAt} and does <b>not</b> extend {@code Auditable}: the table has no {@code updated_at}
 * column on purpose, because an {@code updated_at} would suggest a mutation that must never happen.
 * The parent association is the {@code @ManyToOne} side of the pair, mirroring
 * {@code purchaseorder.model.PurchaseOrderDetail#purchaseOrder}.</p>
 */
@Entity
@Table(name = "access_provisioning_applied_roles")
public class AccessProvisioningAppliedRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private AccessProvisioningTask task;

    @Column(name = "role_name", length = 100, nullable = false)
    private String roleName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Default constructor for JPA
    public AccessProvisioningAppliedRole() {}

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final AccessProvisioningAppliedRole appliedRole = new AccessProvisioningAppliedRole();

        public Builder id(UUID id) {
            appliedRole.id = id;
            return this;
        }

        public Builder task(AccessProvisioningTask task) {
            appliedRole.task = task;
            return this;
        }

        public Builder roleName(String roleName) {
            appliedRole.roleName = roleName;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            appliedRole.createdAt = createdAt;
            return this;
        }

        public AccessProvisioningAppliedRole build() {
            return appliedRole;
        }
    }
}
