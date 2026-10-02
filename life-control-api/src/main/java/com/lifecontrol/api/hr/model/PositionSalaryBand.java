package com.lifecontrol.api.hr.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Company-scoped salary band of a position, mapped to the {@code position_salary_bands} table created
 * by {@code V19__hr_org_structure.sql}.
 *
 * <p>A band joins one {@link Position} to one global {@link SeniorityLevel}. The pair
 * {@code (position_id, seniority_level_id)} is a natural key ({@code uq_position_salary_bands_position_level}),
 * which is why the save upserts on it instead of deleting and re-inserting (decision T10).</p>
 *
 * <p>Money is a {@link BigDecimal} mapped from {@code DECIMAL(12,2)} with a single implied currency
 * (decision T4, gap G2); no currency column exists. The database owns the two range rules through
 * {@code ck_position_salary_bands_range} and {@code ck_position_salary_bands_non_negative}, but the
 * service rejects both first so the client gets a 400 instead of a constraint-violation 409.</p>
 *
 * <p>{@code enabled} is the only state column and the table carries no {@code version} column: these
 * catalog rows have no optimistic-locking precondition (decision T2), so there is no {@code @Version}
 * field here. A row is never deleted; clearing a band flips {@code enabled} to {@code false} (D13).</p>
 */
@Entity
@Table(name = "position_salary_bands")
public class PositionSalaryBand extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id", nullable = false)
    private Position position;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seniority_level_id", nullable = false)
    private SeniorityLevel seniorityLevel;

    @Column(name = "minimum_salary", nullable = false, precision = 12, scale = 2)
    private BigDecimal minimumSalary;

    @Column(name = "maximum_salary", nullable = false, precision = 12, scale = 2)
    private BigDecimal maximumSalary;

    @Column(nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public PositionSalaryBand() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public Position getPosition() {
        return position;
    }

    public SeniorityLevel getSeniorityLevel() {
        return seniorityLevel;
    }

    public BigDecimal getMinimumSalary() {
        return minimumSalary;
    }

    public BigDecimal getMaximumSalary() {
        return maximumSalary;
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

    public void setSeniorityLevel(SeniorityLevel seniorityLevel) {
        this.seniorityLevel = seniorityLevel;
    }

    public void setMinimumSalary(BigDecimal minimumSalary) {
        this.minimumSalary = minimumSalary;
    }

    public void setMaximumSalary(BigDecimal maximumSalary) {
        this.maximumSalary = maximumSalary;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final PositionSalaryBand band = new PositionSalaryBand();

        public Builder id(UUID id) {
            band.id = id;
            return this;
        }

        public Builder position(Position position) {
            band.position = position;
            return this;
        }

        public Builder seniorityLevel(SeniorityLevel seniorityLevel) {
            band.seniorityLevel = seniorityLevel;
            return this;
        }

        public Builder minimumSalary(BigDecimal minimumSalary) {
            band.minimumSalary = minimumSalary;
            return this;
        }

        public Builder maximumSalary(BigDecimal maximumSalary) {
            band.maximumSalary = maximumSalary;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            band.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            band.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            band.setUpdatedAt(updatedAt);
            return this;
        }

        public PositionSalaryBand build() {
            return band;
        }
    }
}
