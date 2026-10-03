package com.lifecontrol.api.hr.repository;

import com.lifecontrol.api.hr.model.Employee;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the company-scoped employee registry.
 *
 * <p>Every finder is company-scoped: an employee is reached through its company, so a foreign
 * employee is already a 404 before any method here runs.</p>
 *
 * <p>The two existence checks are the collision probes of the email generator's loop (record T7).
 * They are deliberately separate reads and not a lock: the write path pre-checks each candidate and
 * picks the first free one without ever retrying a write, and the database's
 * {@code uq_employees_company_email} is the race-safe guarantee (T8).</p>
 *
 * <p>There is deliberately no {@code deleteBy}-style method: an employee is soft-deleted by flipping
 * {@code enabled} to {@code false}.</p>
 */
@Repository
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    Optional<Employee> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * Company-scoped list with optional {@code search}, {@code statusId} and {@code includeDisabled}
     * predicates, ordered deterministically by {@code employeeNumber}.
     *
     * <p>The repository's first company-scoped list that filters, so it is a single JPQL query with
     * optional predicates instead of derived methods. The {@code search} term is normalized by the
     * service to a lowercased {@code %…%} pattern, and the {@code cast(:search as string)} is
     * required: without it this stack cannot infer the type of a null {@code String} parameter in
     * {@code :search IS NULL}. The {@code search} does not escape {@code %} or {@code _}, matching
     * {@code CompanyRepository.findBySearchTerm}'s precedent.</p>
     */
    @Query("""
            SELECT e FROM Employee e
            JOIN FETCH e.status
            WHERE e.company.id = :companyId
              AND (:includeDisabled = true OR e.enabled = true)
              AND (:statusId IS NULL OR e.status.id = :statusId)
              AND (cast(:search as string) IS NULL
                   OR LOWER(e.employeeNumber) LIKE :search
                   OR LOWER(e.firstName) LIKE :search
                   OR LOWER(e.paternalLastName) LIKE :search
                   OR LOWER(e.maternalLastName) LIKE :search
                   OR LOWER(e.email) LIKE :search)
            ORDER BY e.employeeNumber ASC
            """)
    List<Employee> findCompanyEmployees(
            @Param("companyId") UUID companyId,
            @Param("search") String search,
            @Param("statusId") UUID statusId,
            @Param("includeDisabled") boolean includeDisabled);

    boolean existsByCompanyIdAndEmail(UUID companyId, String email);

    boolean existsByCompanyIdAndEmailAndIdNot(UUID companyId, String email, UUID excludeId);

    boolean existsByCompanyIdAndEmployeeNumber(UUID companyId, String employeeNumber);

    boolean existsByCompanyIdAndEmployeeNumberAndIdNot(UUID companyId, String employeeNumber, UUID excludeId);
}
