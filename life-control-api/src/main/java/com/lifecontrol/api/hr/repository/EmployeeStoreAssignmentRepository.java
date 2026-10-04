package com.lifecontrol.api.hr.repository;

import com.lifecontrol.api.hr.model.EmployeeStoreAssignment;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the store-assignment history of an employee.
 *
 * <p>Both finders are employee-scoped, and the employee is resolved through a company-scoped query
 * first, so a foreign employee is already a 404 before either method runs — the same convention
 * {@code ContractRepository} states.</p>
 *
 * <p>Both comparisons use a <b>strict</b> upper bound ({@code validTo > date}) because the column is
 * exclusive: {@code daterange(valid_from, valid_to, '[)')} means the stored {@code validTo} is the
 * first day <b>not</b> covered (decision T2), so a row whose {@code validTo} equals the date does not
 * cover it. Using {@code >=} would re-introduce the one-day hole the half-open convention exists to
 * remove.</p>
 *
 * <p>There is deliberately <b>no</b> overlap query here. The "no two enabled assignments of one
 * {@code (employee, store)} pair may overlap" invariant is the database's partial exclusion
 * constraint {@code ex_employee_store_assignments_no_overlap} (decision T2): the service does not
 * pre-check it and does not translate its violation, because the generic 409 is the honest outcome of
 * a race the pre-check cannot see.</p>
 */
@Repository
public interface EmployeeStoreAssignmentRepository extends JpaRepository<EmployeeStoreAssignment, UUID> {

    /**
     * The employee's enabled assignments whose exclusive range covers {@code date}, as
     * {@code validFrom <= :date AND (validTo IS NULL OR validTo > :date)} — the input of the
     * derivation (decision T6).
     *
     * <p>It returns a {@link List} and not an {@code Optional} because one employee may hold several
     * concurrent stores (decision D1), which is the whole point of the derivation: it emits a claim
     * per assigned store. The {@code JOIN FETCH} loads the store in the same query, because the
     * caller walks it upward to produce the ancestor chain; a derived finder would issue one extra
     * SELECT per assignment.</p>
     */
    @Query("""
            SELECT a FROM EmployeeStoreAssignment a
            JOIN FETCH a.companyStore
            WHERE a.employee.id = :employeeId
              AND a.enabled = true
              AND a.validFrom <= :date
              AND (a.validTo IS NULL OR a.validTo > :date)
            """)
    List<EmployeeStoreAssignment> findEnabledAssignmentsCoveringDate(
            @Param("employeeId") UUID employeeId, @Param("date") LocalDate date);

    /**
     * The employee's enabled assignment for one store whose exclusive range covers {@code date}, as
     * {@code validFrom <= :date AND (validTo IS NULL OR validTo > :date)} — the predecessor finder of
     * the close-the-previous rule (decision T4).
     *
     * <p>Strictly scoped to the store because opening an assignment for a <b>different</b> store
     * closes nothing: that is how a second concurrent store is created (decision D1). It returns a
     * {@link List} and not an {@code Optional} for the same reason {@code ContractRepository} does:
     * the partial exclusion constraint is the only thing that makes its result unique, so a future
     * edge case should degrade to the service handling the extra row, not to a 500 from a non-unique
     * {@code Optional} lookup.</p>
     */
    @Query("""
            SELECT a FROM EmployeeStoreAssignment a
            WHERE a.employee.id = :employeeId
              AND a.companyStore.id = :companyStoreId
              AND a.enabled = true
              AND a.validFrom <= :date
              AND (a.validTo IS NULL OR a.validTo > :date)
            """)
    List<EmployeeStoreAssignment> findEnabledAssignmentForStoreCoveringDate(
            @Param("employeeId") UUID employeeId,
            @Param("companyStoreId") UUID companyStoreId,
            @Param("date") LocalDate date);
}
