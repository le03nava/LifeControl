package com.lifecontrol.api.hr.model;

import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.company.model.Company;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Company-scoped catalog of departments, mapped to the {@code departments} table created by
 * {@code V19__hr_org_structure.sql}.
 *
 * <p>This is the repository's first company-scoped catalog, so a department belongs to exactly one
 * {@link Company} through a lazy {@code @ManyToOne} association instead of a bare {@code company_id}
 * column. Every uniqueness rule lives in the database as a per-company key
 * ({@code uq_departments_company_code}, {@code uq_departments_company_name}), which is what makes the
 * same code legal in two different companies.</p>
 *
 * <p>{@code enabled} is the only state column and the table carries no {@code version} column: these
 * catalog forms have no optimistic-locking precondition (record T2), so there is no {@code @Version}
 * field here.</p>
 */
@Entity
@Table(name = "departments")
public class Department extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "department_code", length = 10, nullable = false)
    private String departmentCode;

    @Column(name = "department_name", length = 100, nullable = false)
    private String departmentName;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Department() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public String getDepartmentName() {
        return departmentName;
    }

    public String getDescription() {
        return description;
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

    public void setCompany(Company company) {
        this.company = company;
    }

    public void setDepartmentCode(String departmentCode) {
        this.departmentCode = departmentCode;
    }

    public void setDepartmentName(String departmentName) {
        this.departmentName = departmentName;
    }

    public void setDescription(String description) {
        this.description = description;
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
        private final Department department = new Department();

        public Builder id(UUID id) {
            department.id = id;
            return this;
        }

        public Builder company(Company company) {
            department.company = company;
            return this;
        }

        public Builder departmentCode(String departmentCode) {
            department.departmentCode = departmentCode;
            return this;
        }

        public Builder departmentName(String departmentName) {
            department.departmentName = departmentName;
            return this;
        }

        public Builder description(String description) {
            department.description = description;
            return this;
        }

        public Builder displayOrder(Integer displayOrder) {
            department.displayOrder = displayOrder;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            department.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            department.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            department.setUpdatedAt(updatedAt);
            return this;
        }

        public Department build() {
            return department;
        }
    }
}
