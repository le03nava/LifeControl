package com.lifecontrol.api.hr.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Company-scoped catalog of positions, mapped to the {@code positions} table created by
 * {@code V19__hr_org_structure.sql}.
 *
 * <p>A position belongs to exactly one {@link Department} through a lazy {@code @ManyToOne}
 * association, and it inherits its company through that department: the record deliberately never
 * duplicates {@code company_id} (decision T7), so the ancestor chain is the single source of truth
 * for the owning company.</p>
 *
 * <p>{@code reportsToPosition} is this repository's <b>first self-referencing association</b>
 * (decision T5). It is a nullable lazy {@code @ManyToOne} back to {@code Position} itself. The
 * database only guarantees the trivial one-node cycle through
 * {@code ck_positions_not_self_reporting}; any deeper cycle and the same-company rule are service
 * concerns (gaps G5/G6). There is no stored depth: depth is derived by walking the chain, never
 * persisted (decision T6).</p>
 *
 * <p>{@code enabled} is the only state column and the table carries no {@code version} column: these
 * catalog forms have no optimistic-locking precondition (decision T2), so there is no
 * {@code @Version} field here.</p>
 */
@Entity
@Table(name = "positions")
public class Position extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Column(name = "position_code", length = 10, nullable = false)
    private String positionCode;

    @Column(name = "position_name", length = 100, nullable = false)
    private String positionName;

    @Column(name = "description", length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reports_to_position_id")
    private Position reportsToPosition;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Position() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public Department getDepartment() {
        return department;
    }

    public String getPositionCode() {
        return positionCode;
    }

    public String getPositionName() {
        return positionName;
    }

    public String getDescription() {
        return description;
    }

    public Position getReportsToPosition() {
        return reportsToPosition;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setDepartment(Department department) {
        this.department = department;
    }

    public void setPositionCode(String positionCode) {
        this.positionCode = positionCode;
    }

    public void setPositionName(String positionName) {
        this.positionName = positionName;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setReportsToPosition(Position reportsToPosition) {
        this.reportsToPosition = reportsToPosition;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Position position = new Position();

        public Builder id(UUID id) {
            position.id = id;
            return this;
        }

        public Builder department(Department department) {
            position.department = department;
            return this;
        }

        public Builder positionCode(String positionCode) {
            position.positionCode = positionCode;
            return this;
        }

        public Builder positionName(String positionName) {
            position.positionName = positionName;
            return this;
        }

        public Builder description(String description) {
            position.description = description;
            return this;
        }

        public Builder reportsToPosition(Position reportsToPosition) {
            position.reportsToPosition = reportsToPosition;
            return this;
        }

        public Builder displayOrder(Integer displayOrder) {
            position.displayOrder = displayOrder;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            position.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            position.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            position.setUpdatedAt(updatedAt);
            return this;
        }

        public Position build() {
            return position;
        }
    }
}
