package com.lifecontrol.api.hr.repository;

import com.lifecontrol.api.hr.model.Contract;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the contract history of an employee.
 *
 * <p>Both finders are employee-scoped, and the employee is resolved through a company-scoped query
 * first, so a foreign employee is already a 404 before either method runs — the same convention
 * {@code PositionSalaryBandRepository} states.</p>
 *
 * <p>The list returns <b>every</b> stored row, disabled ones included, ordered newest first: the
 * history is what the employee detail shows, and a closed or soft-deleted contract is part of it.</p>
 *
 * <p>There is deliberately <b>no</b> overlap query here. The "no two enabled contracts may cover the
 * same day" invariant is the database's partial exclusion constraint
 * {@code ex_employee_contracts_no_overlap} (decision T12): the service does not pre-check it and does
 * not translate its violation, because the generic 409 is the honest outcome of a race the pre-check
 * cannot see.</p>
 *
 * <p>{@link #findEnabledContractCoveringDate(UUID, LocalDate)} is not that overlap check: it is the
 * predecessor finder of the close-the-previous rule (decision T13), a plain JPQL query over the
 * entity's own fields rather than a Postgres range expression. It returns a {@link List} and not an
 * {@code Optional} because the partial exclusion constraint is the only thing that makes its result
 * unique — a future edge case should degrade to the service handling the extra row, not to a 500
 * from a non-unique {@code Optional} lookup.</p>
 */
@Repository
public interface ContractRepository extends JpaRepository<Contract, UUID> {

    /**
     * The employee's contract history, newest first, with the position and the seniority level of
     * every row fetched in the same query.
     *
     * <p>The {@code JOIN FETCH} is there for the caller, not for the query: the response carries the
     * position and level <b>names</b>, read from two LAZY {@code @ManyToOne} associations, so a
     * derived finder would issue one extra SELECT per row per association. This repository already
     * carries that N+1 as gap G13 on the employee list; the contract list does not add a second
     * instance of it. The {@code ORDER BY} reproduces the derived finder's semantics exactly, and
     * the joins are inner joins over {@code NOT NULL} foreign keys, so no row can be dropped.</p>
     */
    @Query("""
            SELECT c FROM Contract c
            JOIN FETCH c.position
            JOIN FETCH c.seniorityLevel
            WHERE c.employee.id = :employeeId
            ORDER BY c.startDate DESC
            """)
    List<Contract> findByEmployeeIdOrderByStartDateDesc(@Param("employeeId") UUID employeeId);

    Optional<Contract> findByEmployeeIdAndId(UUID employeeId, UUID id);

    /**
     * The employee's enabled contract whose range contains {@code startDate}, as
     * {@code startDate <= :startDate AND (endDate IS NULL OR endDate >= :startDate)}.
     *
     * <p>Written on the entity's own fields, in the style of
     * {@code EmployeeRepository.findCompanyEmployees}: it deliberately does not use a Postgres
     * {@code daterange} expression, so the query stays portable and the exclusion constraint
     * ({@code '[)'}) is left as the authority on half-open semantics.</p>
     */
    @Query("""
            SELECT c FROM Contract c
            WHERE c.employee.id = :employeeId
              AND c.enabled = true
              AND c.startDate <= :startDate
              AND (c.endDate IS NULL OR c.endDate >= :startDate)
            """)
    List<Contract> findEnabledContractCoveringDate(
            @Param("employeeId") UUID employeeId, @Param("startDate") LocalDate startDate);
}
