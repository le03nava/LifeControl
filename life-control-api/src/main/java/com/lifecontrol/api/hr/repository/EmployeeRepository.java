package com.lifecontrol.api.hr.repository;

import com.lifecontrol.api.hr.model.Employee;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the company-scoped employee registry.
 *
 * <p>Every finder is company-scoped: an employee is reached through its company, so a foreign
 * employee is already a 404 before any method here runs.</p>
 *
 * <p>The two existence checks are the collision probes of the email generator's retry loop (record
 * T7). They are deliberately separate reads and not a lock: the write path attempts the write and
 * retries with the next numeric suffix, and the database's {@code uq_employees_company_email} is
 * the race-safe guarantee (T8).</p>
 *
 * <p>There is deliberately no {@code deleteBy}-style method: an employee is soft-deleted by flipping
 * {@code enabled} to {@code false}.</p>
 */
@Repository
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    Optional<Employee> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndEmail(UUID companyId, String email);

    boolean existsByCompanyIdAndEmployeeNumber(UUID companyId, String employeeNumber);
}
