package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.exception.ConflictException;
import com.lifecontrol.api.hr.dto.CloseContractRequest;
import com.lifecontrol.api.hr.dto.ContractRequest;
import com.lifecontrol.api.hr.dto.ContractResponse;
import com.lifecontrol.api.hr.exception.ContractNotFoundException;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.exception.PositionNotFoundException;
import com.lifecontrol.api.hr.exception.SeniorityLevelNotFoundException;
import com.lifecontrol.api.hr.model.Contract;
import com.lifecontrol.api.hr.model.ContractType;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.ContractRepository;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.repository.PositionRepository;
import com.lifecontrol.api.hr.repository.SeniorityLevelRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the employment contract history of a company-scoped employee.
 *
 * <p>Every public method starts by resolving the company through {@link #resolveCompany(UUID)},
 * whose first statement is {@link CurrentUserContext#verifyCompanyAccess(UUID)} — the company-scope
 * contract. The employee is then loaded through a {@code companyId}-scoped query
 * ({@code findByIdAndCompanyId}), so a foreign or unknown employee is an
 * {@link EmployeeNotFoundException} (404) rather than a 200, and the contract itself through an
 * employee-scoped query, so a foreign contract is a {@link ContractNotFoundException}.</p>
 *
 * <p>Three rules the database cannot express ({@code G5}) live here. The position must belong to the
 * employee's company, so it is resolved with the company-scoped
 * {@code PositionRepository.findByDepartmentCompanyIdAndId} and a foreign position is a
 * {@link PositionNotFoundException} (404) — the convention {@code PositionSalaryBandRepository}
 * states. A {@code Terminated} employee cannot open a contract, which is a {@link ConflictException}
 * (409), a state conflict like the ones {@code PurchaseOrderService} raises. And the new contract's
 * start must be after the previous one's, which is decision T13's close-the-previous rule.</p>
 *
 * <p><b>T13</b>: opening a contract closes the previous one the day before the new start date, in the
 * same transaction. The employee's enabled contract whose range contains the new start date is found
 * with a plain JPQL query ({@code startDate <= :start AND (endDate IS NULL OR endDate > :start)}),
 * not a Postgres range expression. If there is none, the new row is inserted. If one starts on the
 * new start date, that is a 400 — the only reachable form of T13's "a new contract starting on or
 * before the previous one's start date", because a contract containing the new start date already
 * starts on or before it. Otherwise its stored end bound is set to the new start date itself and the
 * new row is inserted.</p>
 *
 * <p><b>D14</b>: the column's {@code end_date} is <b>exclusive</b> (it is the first day NOT covered,
 * per the frozen {@code daterange(start_date, end_date, '[)')} of T12) while the <b>API is
 * inclusive</b> — {@code endDate} means the last day covered, the way {@code employees.termination_date}
 * reads. This class is the only place the one-day arithmetic lives, at the DTO boundary:
 * {@link #toStoredEndDate(LocalDate)} converts a request's inclusive date to the stored bound, and
 * {@link #toApiEndDate(LocalDate)} converts it back for every response. T13 is stated in covered-day
 * terms above, so storing the successor's start date verbatim as the predecessor's exclusive bound
 * makes the predecessor's last covered day {@code start - 1} and leaves no gap.</p>
 *
 * <p>An overlap the pre-check cannot see — a new <b>open-ended</b> contract whose predecessor is
 * closed but which reaches into a later contract — is deliberately <b>not</b> pre-checked and the
 * {@code DataIntegrityViolationException} is deliberately <b>not</b> caught: the partial exclusion
 * constraint {@code ex_employee_contracts_no_overlap} is the final authority (decision T12) and
 * {@code GlobalExceptionHandler} already maps its violation to 409, exactly as the uniqueness
 * constraints do elsewhere. Catching it here to soften the message would also mask the real
 * rollback, since in PostgreSQL the first violation aborts the whole transaction.</p>
 *
 * <p>{@code contractType} is parsed with {@code ContractType.valueOf(...)}, so an unknown value is an
 * {@code IllegalArgumentException} and therefore a 400 through {@code GlobalExceptionHandler}, the
 * {@code MeasureUnitService} precedent. There is no caching: a company-scoped read must run its scope
 * check on every call, and a {@code @Cacheable} would serve a hit without reaching
 * {@link CurrentUserContext#verifyCompanyAccess(UUID)} — the same decision the neighbouring
 * {@code EmployeeService} carries, pinned by {@code ContractServiceScopedReadCacheTest}.</p>
 */
@Service
public class ContractService {

    private static final Logger logger = LoggerFactory.getLogger(ContractService.class);

    private final ContractRepository contractRepository;
    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final PositionRepository positionRepository;
    private final SeniorityLevelRepository seniorityLevelRepository;
    private final CurrentUserContext currentUserContext;

    public ContractService(
            ContractRepository contractRepository,
            EmployeeRepository employeeRepository,
            CompanyRepository companyRepository,
            PositionRepository positionRepository,
            SeniorityLevelRepository seniorityLevelRepository,
            CurrentUserContext currentUserContext) {
        this.contractRepository = contractRepository;
        this.employeeRepository = employeeRepository;
        this.companyRepository = companyRepository;
        this.positionRepository = positionRepository;
        this.seniorityLevelRepository = seniorityLevelRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Verifies company access and then loads the company, in that order.
     *
     * <p>The access check is deliberately the first statement: a caller without access to the company
     * is denied before any row is loaded. This mirrors {@code EmployeeService.resolveCompany}.</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws CompanyNotFoundException                                when the company does not exist
     */
    private Company resolveCompany(UUID companyId) {
        currentUserContext.verifyCompanyAccess(companyId);
        return companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));
    }

    /**
     * Loads an employee scoped to the company of the path.
     *
     * @throws EmployeeNotFoundException when the employee does not belong to the company
     */
    private Employee resolveEmployee(UUID companyId, UUID employeeId) {
        return employeeRepository
                .findByIdAndCompanyId(employeeId, companyId)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeId));
    }

    /**
     * Returns the whole contract history of the employee, newest first, disabled rows included.
     *
     * <p>The list is deliberately unpaginated and unfiltered: it is a person's record, and a closed
     * or soft-deleted contract is part of it.</p>
     */
    @Transactional(readOnly = true)
    public List<ContractResponse> getContracts(UUID companyId, UUID employeeId) {
        resolveCompany(companyId);
        resolveEmployee(companyId, employeeId);
        return contractRepository.findByEmployeeIdOrderByStartDateDesc(employeeId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Opens a new contract for the employee, closing the previous one the day before (decision T13).
     *
     * @throws ConflictException when the employee is {@code Terminated}
     * @throws PositionNotFoundException when the position is not in the employee's company
     * @throws SeniorityLevelNotFoundException when the level does not exist
     * @throws IllegalArgumentException when the contract type is unknown or the dates are inverted
     */
    @Transactional
    public ContractResponse createContract(UUID companyId, UUID employeeId, ContractRequest request) {
        resolveCompany(companyId);
        var employee = resolveEmployee(companyId, employeeId);

        if (EmployeeStatuses.isTerminated(employee)) {
            throw new ConflictException("A Terminated employee cannot open a contract");
        }

        var position = positionRepository
                .findByDepartmentCompanyIdAndId(companyId, request.positionId())
                .orElseThrow(() -> new PositionNotFoundException(request.positionId()));
        var seniorityLevel = seniorityLevelRepository
                .findById(request.seniorityLevelId())
                .orElseThrow(() -> new SeniorityLevelNotFoundException(request.seniorityLevelId()));
        var contractType = ContractType.valueOf(request.contractType());

        validateDates(request.startDate(), request.endDate());
        closePredecessor(employeeId, request.startDate());

        var contract = Contract.builder()
                .employee(employee)
                .position(position)
                .seniorityLevel(seniorityLevel)
                .contractType(contractType)
                .monthlySalary(request.monthlySalary())
                .startDate(request.startDate())
                .endDate(toStoredEndDate(request.endDate()))
                .enabled(true)
                .build();

        var saved = contractRepository.save(contract);
        logger.info(
                "Contract created: id={}, companyId={}, employeeId={}, startDate={}",
                saved.getId(),
                companyId,
                employeeId,
                saved.getStartDate());

        return toResponse(saved);
    }

    /**
     * Closes an open contract once. Only a contract whose {@code endDate} is {@code null} can be
     * closed; an already-closed one is a 409.
     *
     * <p>The requested end date defaults to today. A date before the contract's start date is a 400,
     * which is also the intended outcome of closing a future contract with no date.</p>
     *
     * @throws ContractNotFoundException when the contract does not belong to the employee
     * @throws ConflictException when the contract is already closed
     * @throws IllegalArgumentException when the end date precedes the contract's start date
     */
    @Transactional
    public ContractResponse closeContract(UUID companyId, UUID employeeId, UUID id, CloseContractRequest request) {
        resolveCompany(companyId);
        resolveEmployee(companyId, employeeId);

        var contract = contractRepository
                .findByEmployeeIdAndId(employeeId, id)
                .orElseThrow(() -> new ContractNotFoundException(id));

        if (contract.getEndDate() != null) {
            throw new ConflictException("Contract " + id + " is already closed");
        }

        var endDate = (request != null && request.endDate() != null) ? request.endDate() : LocalDate.now();
        if (endDate.isBefore(contract.getStartDate())) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }

        // D14: the requested date is inclusive ("last day covered"), so the exclusive stored bound is
        // the day after it. Closing today therefore means today is covered.
        contract.setEndDate(endDate.plusDays(1));
        var saved = contractRepository.save(contract);
        logger.info(
                "Contract closed: id={}, companyId={}, employeeId={}, endDate={}", id, companyId, employeeId, endDate);

        return toResponse(saved);
    }

    /**
     * Decision T13's pre-check: closes the employee's enabled contract covering the new start date the
     * day before it, and rejects a new start that lands on the previous contract's own start date.
     *
     * <p>The finder returns a {@code List} (see {@link ContractRepository}) even though the partial
     * exclusion constraint makes the result unique today; the empty list means "no predecessor", and
     * an unexpected extra row is closed too rather than becoming a 500. The finder's coverage
     * comparison is strict ({@code endDate > :startDate}) because the column is exclusive (D14): a
     * predecessor whose stored bound already equals the new start date does not cover it and is not
     * truncated.</p>
     *
     * <p>The predecessor is written with {@code saveAndFlush}, not {@code save}: Hibernate's action
     * queue executes INSERTs before UPDATEs within one flush, so a deferred close would let the new
     * open-ended row reach the database while the predecessor still covers the range, and the partial
     * exclusion constraint would refuse a write the close-the-previous rule had already made legal.
     * Flushing the close first is what keeps T13 and T12 consistent.</p>
     *
     * <p>The stored bound is the successor's start date <b>verbatim</b> (D14): under the exclusive
     * column that is T13's "the day before the new start date" in covered-day terms — the
     * predecessor's last covered day becomes {@code startDate - 1} — and it leaves no gap.</p>
     */
    private void closePredecessor(UUID employeeId, LocalDate startDate) {
        var predecessors = contractRepository.findEnabledContractCoveringDate(employeeId, startDate);
        for (var predecessor : predecessors) {
            if (predecessor.getStartDate().equals(startDate)) {
                throw new IllegalArgumentException(
                        "A contract cannot start on or before the previous contract's start date");
            }
        }
        for (var predecessor : predecessors) {
            // D14: the exclusive bound IS the successor's start date; the predecessor's last covered
            // day is then startDate - 1, with no gap and no overlap.
            predecessor.setEndDate(startDate);
            contractRepository.saveAndFlush(predecessor);
        }
    }

    private void validateDates(LocalDate startDate, LocalDate endDate) {
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }
    }

    private ContractResponse toResponse(Contract contract) {
        return new ContractResponse(
                contract.getId(),
                contract.getEmployee().getId(),
                contract.getPosition().getId(),
                contract.getPosition().getPositionName(),
                contract.getSeniorityLevel().getId(),
                contract.getSeniorityLevel().getLevelName(),
                contract.getContractType(),
                contract.getMonthlySalary(),
                contract.getStartDate(),
                toApiEndDate(contract.getEndDate()),
                contract.getEnabled());
    }

    /**
     * D14, the DTO boundary in: the API's {@code endDate} is the <b>last day covered</b> (inclusive)
     * and the column stores the <b>first day NOT covered</b> (exclusive), so the stored bound is one
     * day after the requested date. An open-ended contract stays {@code null}. This and
     * {@link #toApiEndDate(LocalDate)} are the only places the one-day arithmetic lives.
     */
    private static LocalDate toStoredEndDate(LocalDate apiEndDate) {
        return apiEndDate == null ? null : apiEndDate.plusDays(1);
    }

    /** D14, the DTO boundary out: the exclusive stored bound is reported as the last day it covers. */
    private static LocalDate toApiEndDate(LocalDate storedEndDate) {
        return storedEndDate == null ? null : storedEndDate.minusDays(1);
    }
}
