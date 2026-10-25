package com.lifecontrol.api.hr.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.store.model.CompanyStore;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Per-level scope assignment of an {@link Employee}, mapped to the {@code employee_scope_assignments}
 * table created by {@code V26__employee_scope_assignments.sql}.
 *
 * <p>It records one thing: from when until when the person is assigned <b>one</b> company-country,
 * company-region, company-zone or company-store. The level is the row's shape — exactly one of the
 * four node associations is set, which is what the database's
 * {@code ck_employee_scope_assignments_one_node} enforces — so there is no {@code level} column and no
 * enum here: a second copy of the same fact could disagree with the columns that produce it.</p>
 *
 * <p>There is deliberately <b>no company level</b>: {@code employees.company_id} is the company fact,
 * exactly one, and a row naming a company would either duplicate it or force
 * {@code CompanyScopeInvariantException} open.</p>
 *
 * <p>This is <b>history</b>, and it carries no {@code version} column and no {@code @Version} field,
 * because its only mutation is being closed once: there is no optimistic-locking precondition to
 * protect. A move to another node is a new row, never an edit — that is the reason the table exists at
 * all.</p>
 *
 * <p>The assignment is <b>currently valid</b> for a date when
 * {@code validFrom <= date AND (validTo IS NULL OR validTo > date)} (the exclusive-bound reading of
 * the {@code '[)'} range), so {@code validTo == null} means <i>open-ended</i> and not <i>current</i>:
 * a closed range whose last covered day has not passed yet is still the current one.</p>
 *
 * <p>The overlap invariant — no two enabled assignments of one {@code (employee, node)} pair may cover
 * the same day — lives in four <b>partial exclusion constraints</b>
 * ({@code ex_employee_scope_assignments_<level>_no_overlap}), one per node column, because a single
 * {@code EXCLUDE} cannot span four alternative columns. The invariant is deliberately <b>not</b> per
 * employee: several concurrent nodes are legal. The entity re-states none of this in Java: the
 * constraints are the authority.</p>
 *
 * <p>No ancestor is stored. The country of a region, and the region and country of a zone, are
 * resolved live by the derivation, because a stored ancestor could disagree with the tree after a
 * reorganisation and the disagreement would be invisible in a column that feeds an authorization
 * decision.</p>
 *
 * <p>The four node associations are lazy {@code @ManyToOne} joins: the node is what the derivation
 * walks upward to produce the claim chain, and the employee is the row's owner. A store row walks
 * {@code store -> zone -> region -> country}, which is the same chain a zone row walks one hop higher.</p>
 */
@Entity
@Table(name = "employee_scope_assignments")
public class EmployeeScopeAssignment extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_country_id")
    private CompanyCountry companyCountry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_region_id")
    private CompanyRegion companyRegion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_zone_id")
    private CompanyZone companyZone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_store_id")
    private CompanyStore companyStore;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public EmployeeScopeAssignment() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public CompanyCountry getCompanyCountry() {
        return companyCountry;
    }

    public CompanyRegion getCompanyRegion() {
        return companyRegion;
    }

    public CompanyZone getCompanyZone() {
        return companyZone;
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

    public void setCompanyCountry(CompanyCountry companyCountry) {
        this.companyCountry = companyCountry;
    }

    public void setCompanyRegion(CompanyRegion companyRegion) {
        this.companyRegion = companyRegion;
    }

    public void setCompanyZone(CompanyZone companyZone) {
        this.companyZone = companyZone;
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
        private final EmployeeScopeAssignment assignment = new EmployeeScopeAssignment();

        public Builder id(UUID id) {
            assignment.id = id;
            return this;
        }

        public Builder employee(Employee employee) {
            assignment.employee = employee;
            return this;
        }

        public Builder companyCountry(CompanyCountry companyCountry) {
            assignment.companyCountry = companyCountry;
            return this;
        }

        public Builder companyRegion(CompanyRegion companyRegion) {
            assignment.companyRegion = companyRegion;
            return this;
        }

        public Builder companyZone(CompanyZone companyZone) {
            assignment.companyZone = companyZone;
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

        public EmployeeScopeAssignment build() {
            return assignment;
        }
    }
}
