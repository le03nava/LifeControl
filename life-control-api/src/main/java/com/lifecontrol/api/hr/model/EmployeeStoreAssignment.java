package com.lifecontrol.api.hr.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.store.model.CompanyStore;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Store assignment of an {@link Employee}, mapped to the {@code employee_store_assignments} table
 * created by {@code V23__employee_store_assignments.sql}.
 *
 * <p>This is <b>history</b>, and it carries no {@code version} column and no {@code @Version} field
 * (decision T1): its only mutation is being closed once, so there is no optimistic-locking
 * precondition to protect. A transfer is a new row, never an edit — that is the reason the table
 * exists at all.</p>
 *
 * <p>The assignment is <b>currently valid</b> for a date when
 * {@code validFrom <= date AND (validTo IS NULL OR validTo > date)} (the exclusive-bound reading of
 * the {@code '[)'} range, decision T3), so {@code validTo == null} means <i>open-ended</i> and not
 * <i>current</i>: a closed range whose last covered day has not passed yet is still the current one.
 * The derivation that feeds the token claims reads exactly this (decision T6).</p>
 *
 * <p>The overlap invariant — no two enabled assignments of one {@code (employee, store)} pair may
 * cover the same day — is the database's <b>partial exclusion constraint</b>
 * {@code ex_employee_store_assignments_no_overlap} (decision T2), on
 * {@code daterange(valid_from, valid_to, '[)')} per {@code (employee, company_store)} and
 * {@code WHERE (enabled)}. The end date is exclusive, so an assignment whose {@code validTo} equals
 * the next one's {@code validFrom} does not overlap it, and a soft-deleted assignment stops blocking
 * its replacement. The invariant is deliberately <b>not</b> per employee: several concurrent stores
 * are legal (decision D1). The entity re-states none of this in Java: the constraint is the
 * authority.</p>
 *
 * <p>The store tree answers everything else. This entity therefore stores <b>no</b> ancestors
 * (decision T7): no {@code company_id}, no country, region or zone. A stored ancestor could
 * disagree with the tree after a reorganisation, and the disagreement would be invisible in a column
 * that feeds an authorization decision.</p>
 *
 * <p>The two associations are lazy {@code @ManyToOne} joins: the store is what the derivation walks
 * upward to produce the claim chain, and the employee is the row's owner.</p>
 */
@Entity
@Table(name = "employee_store_assignments")
public class EmployeeStoreAssignment extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_store_id", nullable = false)
    private CompanyStore companyStore;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public EmployeeStoreAssignment() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public CompanyStore getCompanyStore() {
        return companyStore;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
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

    public void setCompanyStore(CompanyStore companyStore) {
        this.companyStore = companyStore;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final EmployeeStoreAssignment assignment = new EmployeeStoreAssignment();

        public Builder id(UUID id) {
            assignment.id = id;
            return this;
        }

        public Builder employee(Employee employee) {
            assignment.employee = employee;
            return this;
        }

        public Builder companyStore(CompanyStore companyStore) {
            assignment.companyStore = companyStore;
            return this;
        }

        public Builder validFrom(LocalDate validFrom) {
            assignment.validFrom = validFrom;
            return this;
        }

        public Builder validTo(LocalDate validTo) {
            assignment.validTo = validTo;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            assignment.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            assignment.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            assignment.setUpdatedAt(updatedAt);
            return this;
        }

        public EmployeeStoreAssignment build() {
            return assignment;
        }
    }
}
