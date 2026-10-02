package com.lifecontrol.api.hr.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Role-template row of a company-scoped position, mapped to the {@code position_roles} table created
 * by {@code V19__hr_org_structure.sql}.
 *
 * <p>A row joins one {@link Position} to one business {@code roleName}. The pair
 * {@code (position_id, role_name)} is a natural key ({@code uq_position_roles}), which is why the
 * save upserts on it instead of deleting and re-inserting (decision T10, record T20).</p>
 *
 * <p>The table carries <b>no</b> scope or client column (decision T8): every grantable role is a
 * client role of the application client, and modelling scope would prepare a case that must not be
 * permitted. {@code enabled} is the only state column and the table carries no {@code version}
 * column (decision T2): these template rows have no optimistic-locking precondition. A row is never
 * deleted; clearing a role flips {@code enabled} to {@code false}.</p>
 *
 * <p>This is a <b>provisioning seed, not a live authority</b> (decision D6): changing a position's
 * template here does not touch anyone's access in Keycloak.</p>
 */
@Entity
@Table(name = "position_roles")
public class PositionRole extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id", nullable = false)
    private Position position;

    @Column(name = "role_name", nullable = false, length = 100)
    private String roleName;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public PositionRole() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public Position getPosition() {
        return position;
    }

    public String getRoleName() {
        return roleName;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final PositionRole role = new PositionRole();

        public Builder id(UUID id) {
            role.id = id;
            return this;
        }

        public Builder position(Position position) {
            role.position = position;
            return this;
        }

        public Builder roleName(String roleName) {
            role.roleName = roleName;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            role.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            role.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            role.setUpdatedAt(updatedAt);
            return this;
        }

        public PositionRole build() {
            return role;
        }
    }
}
