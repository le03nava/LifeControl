package com.lifecontrol.api.hr.repository;

import com.lifecontrol.api.hr.model.Contract;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
 * {@code ex_employee_contracts_no_overlap} (decision T12), and the close-the-previous rule that
 * precedes a new contract (decision T13) belongs to the service, not to a repository query.</p>
 */
@Repository
public interface ContractRepository extends JpaRepository<Contract, UUID> {

    List<Contract> findByEmployeeIdOrderByStartDateDesc(UUID employeeId);

    Optional<Contract> findByEmployeeIdAndId(UUID employeeId, UUID id);
}
