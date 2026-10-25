package com.lifecontrol.api.hr.repository;

import com.lifecontrol.api.hr.model.EmployeeScopeAssignment;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the per-level scope-assignment history of an employee.
 *
 * <p>Both finds and the history read are employee-scoped, and the employee is resolved through a
 * company-scoped query first, so a foreign employee is already a 404 before either method runs — the
 * same convention {@link EmployeeStoreAssignmentRepository} states.</p>
 *
 * <p>Every comparison uses a <b>strict</b> upper bound ({@code validTo > date}) because the column is
 * exclusive: {@code daterange(valid_from, valid_to, '[)')} means the stored {@code validTo} is the
 * first day <b>not</b> covered, so a row whose {@code validTo} equals the date does not cover it.</p>
 *
 * <p>There is deliberately <b>no</b> overlap query here. The "no two enabled assignments of one
 * {@code (employee, node)} pair may overlap" invariant is the database's four partial exclusion
 * constraints (one per level, named {@code ex_employee_scope_assignments_<level>_no_overlap}): the
 * service does not pre-check it and does not translate its violation, because the generic 409 is the
 * honest outcome of a race the pre-check cannot see.</p>
 *
 * <p>No method here answers "which level is this row": the level is the row's shape, so the callers
 * read the four node associations and never a stored label.</p>
 */
@Repository
public interface EmployeeScopeAssignmentRepository extends JpaRepository<EmployeeScopeAssignment, UUID> {

    /**
     * The employee's enabled assignments whose exclusive range covers {@code date}, as
     * {@code validFrom <= :date AND (validTo IS NULL OR validTo > :date)} — the input of
     * {@code ScopeAssignmentDerivation} and therefore of the membership projection and of the Access
     * section's read.
     *
     * <p>It returns a {@link List} and not an {@code Optional} because one employee may hold several
     * nodes at once, at the same level or at different ones, and the derivation emits a claim per
     * assigned node. The {@code LEFT JOIN FETCH} loads each node <b>and its ancestor chain</b> in the
     * same query, because the derivation walks the chain of every row and the display mapper reads
     * every ancestor's id and name; a derived finder would issue one extra SELECT per row per lazy
     * association. The joins are {@code LEFT} joins because a row sets exactly one of the four node
     * columns, so an inner join would drop rows.</p>
     *
     * <p>The {@code ORDER BY} makes the read deterministic — newest first, with {@code a.id DESC} as
     * the tie-breaker — so the claim lists the projection writes never depend on the database's
     * arbitrary row order. It is not a business ordering: the caller reads a set.</p>
     */
    @Query("""
            SELECT a FROM EmployeeScopeAssignment a
            LEFT JOIN FETCH a.companyCountry country
            LEFT JOIN FETCH country.country countryCatalog
            LEFT JOIN FETCH country.company countryCompany
            LEFT JOIN FETCH a.companyRegion region
            LEFT JOIN FETCH region.companyCountry regionCountry
            LEFT JOIN FETCH regionCountry.country regionCountryCatalog
            LEFT JOIN FETCH regionCountry.company regionCompany
            LEFT JOIN FETCH a.companyZone zone
            LEFT JOIN FETCH zone.companyRegion zoneRegion
            LEFT JOIN FETCH zoneRegion.companyCountry zoneCountry
            LEFT JOIN FETCH zoneCountry.country zoneCountryCatalog
            LEFT JOIN FETCH zoneCountry.company zoneCompany
            LEFT JOIN FETCH a.companyStore store
            LEFT JOIN FETCH store.companyZone storeZone
            LEFT JOIN FETCH storeZone.companyRegion storeRegion
            LEFT JOIN FETCH storeRegion.companyCountry storeCountry
            LEFT JOIN FETCH storeCountry.country storeCountryCatalog
            LEFT JOIN FETCH storeCountry.company storeCompany
            WHERE a.employee.id = :employeeId
              AND a.enabled = true
              AND a.validFrom <= :date
              AND (a.validTo IS NULL OR a.validTo > :date)
            ORDER BY a.validFrom DESC, a.id DESC
            """)
    List<EmployeeScopeAssignment> findEnabledAssignmentsCoveringDate(
            @Param("employeeId") UUID employeeId, @Param("date") LocalDate date);

    /**
     * The employee's enabled assignment for one node whose exclusive range covers {@code date} — the
     * predecessor finder of the close-the-previous rule.
     *
     * <p>The node id is compared against all four columns in one predicate. That is safe because a
     * node id is a UUID primary key of exactly one of {@code company_countries},
     * {@code company_regions}, {@code company_zones} or {@code company_stores}, and
     * {@code ck_employee_scope_assignments_one_node} guarantees a stored row sets exactly one of the
     * four columns, so a value can match at most one column of a row. One finder instead of four
     * keeps the close-the-previous rule in one place.</p>
     *
     * <p>Strictly scoped to the node because opening an assignment for a <b>different</b> node closes
     * nothing: that is how a second concurrent node is created. It returns a {@link List} and not an
     * {@code Optional} for the same reason the store finder does: the exclusion constraint is the only
     * thing that makes the result unique, so a future edge case should degrade to the service closing
     * the extra row, not to a 500 from a non-unique {@code Optional} lookup.</p>
     */
    @Query("""
            SELECT a FROM EmployeeScopeAssignment a
            WHERE a.employee.id = :employeeId
              AND (a.companyCountry.id = :nodeId
                   OR a.companyRegion.id = :nodeId
                   OR a.companyZone.id = :nodeId
                   OR a.companyStore.id = :nodeId)
              AND a.enabled = true
              AND a.validFrom <= :date
              AND (a.validTo IS NULL OR a.validTo > :date)
            """)
    List<EmployeeScopeAssignment> findEnabledAssignmentForNodeCoveringDate(
            @Param("employeeId") UUID employeeId, @Param("nodeId") UUID nodeId, @Param("date") LocalDate date);

    /**
     * The employee's assignment history, newest first, with each node <b>and its whole ancestor
     * chain</b> fetched in the same query.
     *
     * <p>The chain is fetched for the caller, not for the query: {@code ScopeAssignmentResponse}'
     * nested {@code derived} value reads every ancestor's id and name, so a derived finder would issue
     * one extra SELECT per row per lazy association. The joins are {@code LEFT} joins because a row
     * sets exactly one node column. The {@code includeDisabled} predicate is the JPQL boolean the store
     * history read already uses for the same flag. There is deliberately <b>no</b> caching anywhere on
     * this read: the derivation feeds an authorization input, and a cache that outlives the decision is
     * how {@code hr-org-structure}'s G12 happens.</p>
     */
    @Query("""
            SELECT a FROM EmployeeScopeAssignment a
            LEFT JOIN FETCH a.companyCountry country
            LEFT JOIN FETCH country.country countryCatalog
            LEFT JOIN FETCH country.company countryCompany
            LEFT JOIN FETCH a.companyRegion region
            LEFT JOIN FETCH region.companyCountry regionCountry
            LEFT JOIN FETCH regionCountry.country regionCountryCatalog
            LEFT JOIN FETCH regionCountry.company regionCompany
            LEFT JOIN FETCH a.companyZone zone
            LEFT JOIN FETCH zone.companyRegion zoneRegion
            LEFT JOIN FETCH zoneRegion.companyCountry zoneCountry
            LEFT JOIN FETCH zoneCountry.country zoneCountryCatalog
            LEFT JOIN FETCH zoneCountry.company zoneCompany
            LEFT JOIN FETCH a.companyStore store
            LEFT JOIN FETCH store.companyZone storeZone
            LEFT JOIN FETCH storeZone.companyRegion storeRegion
            LEFT JOIN FETCH storeRegion.companyCountry storeCountry
            LEFT JOIN FETCH storeCountry.country storeCountryCatalog
            LEFT JOIN FETCH storeCountry.company storeCompany
            WHERE a.employee.id = :employeeId
              AND (:includeDisabled = true OR a.enabled = true)
            ORDER BY a.validFrom DESC, a.id DESC
            """)
    List<EmployeeScopeAssignment> findEmployeeAssignments(
            @Param("employeeId") UUID employeeId, @Param("includeDisabled") boolean includeDisabled);

    /**
     * One assignment of the employee by id, the employee-scoped lookup of the close path: a foreign
     * assignment is a 404 here instead of a 200.
     */
    Optional<EmployeeScopeAssignment> findByEmployeeIdAndId(UUID employeeId, UUID id);
}
