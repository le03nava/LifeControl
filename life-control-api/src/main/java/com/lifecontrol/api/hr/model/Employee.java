package com.lifecontrol.api.hr.model;

import com.lifecontrol.api.common.address.model.Address;
import com.lifecontrol.api.common.model.Auditable;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.status.model.Status;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Company-scoped HR record of a person, mapped to the {@code employees} table created by
 * {@code V20__employee_registry.sql}.
 *
 * <p>This is the schema's first person-&gt;company model. A row belongs to exactly one
 * {@link Company} through a lazy {@code @ManyToOne} association (decision D4: a person holds one
 * company, the decided ceiling of the model), and {@code employee_number} is typed by the operator,
 * never generated (decision D5), with the per-company {@code UNIQUE} as the real guarantee.</p>
 *
 * <p>The table carries a {@code version} column and the entity exposes it as an optimistic-locking
 * precondition (decision T1, T2): an employee is a rich edited aggregate with a form, unlike the
 * history rows of a contract.</p>
 *
 * <p>The life cycle comes from the {@code statuses} catalogue through the {@code EMPLOYEE_STATUS}
 * family seeded by the same migration (decision T3). {@code enabled} is a different fact:
 * {@code enabled = false} means the row was deleted, while a {@code Terminated} employee stays
 * {@code enabled = true} because their history must remain readable.</p>
 *
 * <p>{@link #email} is the generated corporate address and is frozen once {@code keycloakUserId} is
 * set (decision T9): changing the address after provisioning would desynchronize the login.</p>
 *
 * <p>{@link #keycloakUserId} is <b>never operator-typable</b> (decision T17). It has a getter and a
 * builder method but deliberately <b>no public setter</b>, and it is written only by the
 * access-provisioning flow — create the Keycloak account, or link the existing one — never by the
 * employee record path.</p>
 */
@Entity
@Table(name = "employees")
public class Employee extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "employee_number", length = 30, nullable = false)
    private String employeeNumber;

    @Column(name = "first_name", length = 100, nullable = false)
    private String firstName;

    @Column(name = "paternal_last_name", length = 100, nullable = false)
    private String paternalLastName;

    @Column(name = "maternal_last_name", length = 100)
    private String maternalLastName;

    @Column(name = "email", length = 255, nullable = false)
    private String email;

    @Column(name = "phone_number", length = 50)
    private String phoneNumber;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(name = "hire_date", nullable = false)
    private LocalDate hireDate;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    @OneToOne(
            cascade = {CascadeType.PERSIST, CascadeType.MERGE},
            fetch = FetchType.LAZY)
    @JoinColumn(name = "address_id")
    private Address address;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employment_status_id", nullable = false)
    private Status status;

    @Column(name = "keycloak_user_id", length = 36, unique = true)
    private String keycloakUserId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    // Default constructor for JPA
    public Employee() {}

    // Getters
    public UUID getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getPaternalLastName() {
        return paternalLastName;
    }

    public String getMaternalLastName() {
        return maternalLastName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public LocalDate getHireDate() {
        return hireDate;
    }

    public LocalDate getTerminationDate() {
        return terminationDate;
    }

    public Address getAddress() {
        return address;
    }

    public Status getStatus() {
        return status;
    }

    /**
     * The Keycloak account linked to this employee, or {@code null} when none exists yet. There is
     * deliberately no public setter: the column is written only by the access-provisioning flow,
     * never by the employee record path (decision T17).
     */
    public String getKeycloakUserId() {
        return keycloakUserId;
    }

    public Long getVersion() {
        return version;
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

    public void setEmployeeNumber(String employeeNumber) {
        this.employeeNumber = employeeNumber;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public void setPaternalLastName(String paternalLastName) {
        this.paternalLastName = paternalLastName;
    }

    public void setMaternalLastName(String maternalLastName) {
        this.maternalLastName = maternalLastName;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public void setHireDate(LocalDate hireDate) {
        this.hireDate = hireDate;
    }

    public void setTerminationDate(LocalDate terminationDate) {
        this.terminationDate = terminationDate;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Employee employee = new Employee();

        public Builder id(UUID id) {
            employee.id = id;
            return this;
        }

        public Builder company(Company company) {
            employee.company = company;
            return this;
        }

        public Builder employeeNumber(String employeeNumber) {
            employee.employeeNumber = employeeNumber;
            return this;
        }

        public Builder firstName(String firstName) {
            employee.firstName = firstName;
            return this;
        }

        public Builder paternalLastName(String paternalLastName) {
            employee.paternalLastName = paternalLastName;
            return this;
        }

        public Builder maternalLastName(String maternalLastName) {
            employee.maternalLastName = maternalLastName;
            return this;
        }

        public Builder email(String email) {
            employee.email = email;
            return this;
        }

        public Builder phoneNumber(String phoneNumber) {
            employee.phoneNumber = phoneNumber;
            return this;
        }

        public Builder birthDate(LocalDate birthDate) {
            employee.birthDate = birthDate;
            return this;
        }

        public Builder hireDate(LocalDate hireDate) {
            employee.hireDate = hireDate;
            return this;
        }

        public Builder terminationDate(LocalDate terminationDate) {
            employee.terminationDate = terminationDate;
            return this;
        }

        public Builder address(Address address) {
            employee.address = address;
            return this;
        }

        public Builder status(Status status) {
            employee.status = status;
            return this;
        }

        /**
         * Assigns the linked Keycloak account. Reachable only from the access-provisioning flow: it
         * is on the builder because nothing else may set the column, and no public setter exists
         * (decision T17).
         */
        public Builder keycloakUserId(String keycloakUserId) {
            employee.keycloakUserId = keycloakUserId;
            return this;
        }

        public Builder version(Long version) {
            employee.version = version;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            employee.enabled = enabled;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            employee.setCreatedAt(createdAt);
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            employee.setUpdatedAt(updatedAt);
            return this;
        }

        public Employee build() {
            return employee;
        }
    }
}
