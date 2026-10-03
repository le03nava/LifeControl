package com.lifecontrol.api.hr.model;

import com.lifecontrol.api.common.model.Auditable;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Employment contract of an {@link Employee}, mapped to the {@code employee_contracts} table created
 * by {@code V21__employee_contracts.sql}.
 *
 * <p>This is <b>history</b>, and it carries no {@code version} column and no {@code @Version} field
 * (decision T2): its only mutation is being closed once, so there is no optimistic-locking
 * precondition to protect. A salary change or a promotion is a new row, never an edit — that is the
 * reason the table exists at all (decision D4 reads a person's position and department through the
 * contract, never from the employee).</p>
 *
 * <p>The <b>current</b> contract is the row whose date range contains today (decision T11),
 * {@code start_date <= CURRENT_DATE AND (end_date IS NULL OR end_date >= CURRENT_DATE)}, so
 * {@code endDate == null} means <i>open-ended</i> and not <i>current</i>: a fixed-term contract with
 * a known end date is still the current one until that date passes.</p>
 *
 * <p>The overlap invariant — no two enabled contracts of one employee may cover the same day — is
 * the database's <b>partial exclusion constraint</b> {@code ex_employee_contracts_no_overlap}
 * (decision T12), on {@code daterange(start_date, end_date, '[)')} per employee and
 * {@code WHERE (enabled)}. The end date is exclusive, so a contract whose {@code end_date} equals the
 * next contract's {@code start_date} does not overlap it, and a soft-deleted contract stops blocking
 * its replacement. The entity deliberately re-states none of this in Java: the constraint is the
 * authority.</p>
 *
 * <p>The three associations are lazy {@code @ManyToOne} joins and {@code contractType} is a
 * {@code VARCHAR(30)} validated by a Java enum, following the
 * {@code inventory_movements.movement_type} + {@code MovementType} precedent: this schema contains no
 * native enum and no CHECK on a type column.</p>
 */
@Entity
@Table(name = "employee_contracts")
public class Contract extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id", nullable = false)
    private Position position;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seniority_level_id", nullable = false)
    private SeniorityLevel seniorityLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type", length = 30, nullable = false)
    private ContractType contractType;

    @Column(name = "monthly_salary", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlySalary;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Contract() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public Position getPosition() {
        return position;
    }

    public SeniorityLevel getSeniorityLevel() {
        return seniorityLevel;
    }

    public ContractType getContractType() {
        return contractType;
    }

    public BigDecimal getMonthlySalary() {
        return monthlySalary;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    // Setters
    public void setId(UUID id) {
        this.id = id;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    public void setSeniorityLevel(SeniorityLevel seniorityLevel) {
        this.seniorityLevel = seniorityLevel;
    }

    public void setContractType(ContractType contractType) {
        this.contractType = contractType;
    }

    public void setMonthlySalary(BigDecimal monthlySalary) {
        this.monthlySalary = monthlySalary;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Contract contract = new Contract();

        public Builder id(UUID id) {
            contract.id = id;
            return this;
        }

        public Builder employee(Employee employee) {
            contract.employee = employee;
            return this;
        }

        public Builder position(Position position) {
            contract.position = position;
            return this;
        }

        public Builder seniorityLevel(SeniorityLevel seniorityLevel) {
            contract.seniorityLevel = seniorityLevel;
            return this;
        }

        public Builder contractType(ContractType contractType) {
            contract.contractType = contractType;
            return this;
        }

        public Builder monthlySalary(BigDecimal monthlySalary) {
            contract.monthlySalary = monthlySalary;
            return this;
        }

        public Builder startDate(LocalDate startDate) {
            contract.startDate = startDate;
            return this;
        }

        public Builder endDate(LocalDate endDate) {
            contract.endDate = endDate;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            contract.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            contract.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            contract.setUpdatedAt(updatedAt);
            return this;
        }

        public Contract build() {
            return contract;
        }
    }
}
