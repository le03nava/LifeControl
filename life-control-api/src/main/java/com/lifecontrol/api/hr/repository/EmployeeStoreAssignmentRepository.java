package com.lifecontrol.api.hr.repository;

import com.lifecontrol.api.hr.model.EmployeeStoreAssignment;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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

    /**
     * The employee's assignment history, newest first, with the store <b>and its whole ancestor
     * chain</b> fetched in the same query.
     *
     * <p>The chain is fetched for the caller, not for the query: {@code StoreAssignmentResponse}'
     * nested {@code derived} value reads every ancestor's id and name (decision T14), so a derived
     * finder would issue one extra SELECT per row per lazy association. The joins are inner joins
     * over {@code NOT NULL} foreign keys, so no row can be dropped, and the fetch covers the country
     * entity as well because the display name of the company-country comes from it.</p>
     *
     * <p>The {@code includeDisabled} predicate is a JPQL boolean, the shape
     * {@code EmployeeRepository.findCompanyEmployees} already uses for the same flag, so the history
     * can be read either wholly or with the soft-deleted rows hidden. There is deliberately <b>no</b>
     * caching anywhere on this read (decision T10): the derivation feeds an authorization input, and
     * a cache that outlives the decision is how {@code hr-org-structure}'s G12 happens. The
     * {@code ORDER BY} reproduces the contract history's newest-first shape; the {@code a.id DESC}
     * tie-breaker exists only so repeated reads of rows sharing a {@code validFrom} come back in a
     * deterministic order, not as a business ordering.</p>
     */
    @Query("""
            SELECT a FROM EmployeeStoreAssignment a
            JOIN FETCH a.companyStore s
            JOIN FETCH s.companyZone z
            JOIN FETCH z.companyRegion r
            JOIN FETCH r.companyCountry c
            JOIN FETCH c.country
            JOIN FETCH c.company
            WHERE a.employee.id = :employeeId
              AND (:includeDisabled = true OR a.enabled = true)
            ORDER BY a.validFrom DESC, a.id DESC
            """)
    List<EmployeeStoreAssignment> findEmployeeAssignments(
            @Param("employeeId") UUID employeeId, @Param("includeDisabled") boolean includeDisabled);

    /**
     * One assignment of the employee by id, the employee-scoped lookup of the close path.
     *
     * <p>Scoped to the employee for the same reason {@code ContractRepository} scopes its own: the
     * employee is already resolved through a company-scoped query, so a foreign assignment is a 404
     * here instead of a 200.</p>
     */
    Optional<EmployeeStoreAssignment> findByEmployeeIdAndId(UUID employeeId, UUID id);
}
