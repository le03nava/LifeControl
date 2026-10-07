package com.lifecontrol.api.provisioning.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.hr.model.Employee;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Durable intent of the employee-access flow, mapped to the {@code access_provisioning_tasks} table
 * created by {@code V22__employee_access_provisioning.sql}.
 *
 * <p>The row is written in the same transaction that asserts the organisational fact — a contract
 * activation, a store-assignment change, a termination — and a worker resolves it by
 * <b>reconciling</b> Keycloak against the current truth (record T2/T4), never by replaying an
 * instruction. That is why the row carries a <b>reference</b> (the {@link #employee} and the
 * {@link #kind}) and never the role names (record T3).</p>
 *
 * <p>The row is also the audit (record T9): {@link #requestedBy}, {@link #requestedAt},
 * {@link #decidedBy}, {@link #decidedAt} and {@link #appliedAt} record who asked, who approved and
 * when it landed, because the existing {@code activity_logs} is HTTP-shaped and cannot answer "who
 * granted this".</p>
 *
 * <p>The table carries <b>no {@code version} column and this entity has no {@code @Version}</b>
 * (record T2): the concurrency control for a worker claiming a task belongs to W4 and is a
 * conditional update on {@link #status} — the claim itself — so an optimistic-locking precondition
 * would be a second, redundant guard. {@code V8__store_optimistic_locking.sql} is the precedent for
 * adding one later if that decision changes.</p>
 *
 * <p>{@link #kind} is the task's immutable reference (record T3) and therefore has a getter and a
 * builder method but <b>no setter</b>, mirroring {@code Employee#keycloakUserId}: re-typing an
 * {@code ACTIVATE} into a {@code DEACTIVATE} would rewrite the intent after the fact.</p>
 *
 * <p>{@link #status} and {@link #kind} are VARCHAR columns validated by the Java enums
 * {@link AccessProvisioningTaskStatus} and {@link AccessProvisioningTaskKind}: the table has no CHECK
 * and no native enum, following the {@code inventory_movements.movement_type} precedent. The enum
 * values are the column types, not a state machine — the machine is W1b's.</p>
 *
 * <p>{@link #appliedRoles} is the applied snapshot as a child table (record T8): immutable history,
 * one row per role the task granted or removed, read back through its own repository. The association
 * is lazy and cascades nothing, mirroring {@code PurchaseOrder#details}.</p>
 */
@Entity
@Table(name = "access_provisioning_tasks")
public class AccessProvisioningTask extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20, nullable = false)
    private AccessProvisioningTaskKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AccessProvisioningTaskStatus status;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "requested_by", length = 36, nullable = false)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    /**
     * The retry deadline (record T42), mapped to {@code next_attempt_at}.
     *
     * <p>The invariant is that this column is written only on the {@code FAILED → PENDING} edge and
     * cleared by the claim: a {@code RUNNING} row carries no deadline, and a set value means
     * "{@code PENDING} and waiting until then". {@code null} therefore reads as <b>due
     * immediately</b> — the state a task is created in and the state the operator's manual retry
     * leaves it in (record T46).</p>
     */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @OneToMany(mappedBy = "task", fetch = FetchType.LAZY)
    private List<AccessProvisioningAppliedRole> appliedRoles = new ArrayList<>();

    // Default constructor for JPA
    public AccessProvisioningTask() {}

    /**
     * Fills {@link #requestedAt} at persist time so the {@code requested_at} NOT NULL column is never
     * written as an explicit {@code NULL}.
     *
     * <p>This method <strong>must not</strong> be named {@code onCreate}: {@link Auditable} already
     * declares {@code @PrePersist protected void onCreate()}, so a method with that same signature
     * here would <em>override</em> it and {@code Auditable}'s body would never run, leaving
     * {@code created_at} / {@code updated_at} unset and failing their NOT NULL columns. A distinct
     * name keeps both callbacks active. The set-if-null guard means an explicitly supplied value
     * still wins.</p>
     */
    @PrePersist
    protected void initializeRequestedAt() {
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
    }

    // Getters
    public UUID getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    /**
     * The kind of work this task represents. There is deliberately no setter: the kind is the task's
     * immutable reference (record T3), written only at creation through the builder.
     */
    public AccessProvisioningTaskKind getKind() {
        return kind;
    }

    public AccessProvisioningTaskStatus getStatus() {
        return status;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public LocalDateTime getAppliedAt() {
        return appliedAt;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public List<AccessProvisioningAppliedRole> getAppliedRoles() {
        return appliedRoles;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public void setStatus(AccessProvisioningTaskStatus status) {
        this.status = status;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public void setAppliedAt(LocalDateTime appliedAt) {
        this.appliedAt = appliedAt;
    }

    public void setNextAttemptAt(LocalDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public void setAppliedRoles(List<AccessProvisioningAppliedRole> appliedRoles) {
        this.appliedRoles = appliedRoles;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final AccessProvisioningTask task = new AccessProvisioningTask();

        public Builder id(UUID id) {
            task.id = id;
            return this;
        }

        public Builder employee(Employee employee) {
            task.employee = employee;
            return this;
        }

        /**
         * Assigns the kind. Reachable only from the creation flow: it is on the builder because the
         * kind is immutable after creation (record T3), and no setter exists.
         */
        public Builder kind(AccessProvisioningTaskKind kind) {
            task.kind = kind;
            return this;
        }

        public Builder status(AccessProvisioningTaskStatus status) {
            task.status = status;
            return this;
        }

        public Builder attempts(Integer attempts) {
            task.attempts = attempts;
            return this;
        }

        public Builder lastError(String lastError) {
            task.lastError = lastError;
            return this;
        }

        public Builder requestedBy(String requestedBy) {
            task.requestedBy = requestedBy;
            return this;
        }

        public Builder requestedAt(LocalDateTime requestedAt) {
            task.requestedAt = requestedAt;
            return this;
        }

        public Builder decidedBy(String decidedBy) {
            task.decidedBy = decidedBy;
            return this;
        }

        public Builder decidedAt(LocalDateTime decidedAt) {
            task.decidedAt = decidedAt;
            return this;
        }

        public Builder appliedAt(LocalDateTime appliedAt) {
            task.appliedAt = appliedAt;
            return this;
        }

        public Builder nextAttemptAt(LocalDateTime nextAttemptAt) {
            task.nextAttemptAt = nextAttemptAt;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            task.enabled = enabled;
            return this;
        }

        public Builder appliedRoles(List<AccessProvisioningAppliedRole> appliedRoles) {
            task.appliedRoles = appliedRoles;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            task.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            task.setUpdatedAt(updatedAt);
            return this;
        }

        public AccessProvisioningTask build() {
            return task;
        }
    }
}
