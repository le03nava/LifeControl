package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.exception.ConflictException;
import com.lifecontrol.api.hr.dto.CloseStoreAssignmentRequest;
import com.lifecontrol.api.hr.dto.StoreAssignmentRequest;
import com.lifecontrol.api.hr.dto.StoreAssignmentResponse;
import com.lifecontrol.api.hr.dto.StoreAssignmentResponse.DerivedStoreScope;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.exception.StoreAssignmentNotFoundException;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.model.EmployeeStoreAssignment;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.repository.EmployeeStoreAssignmentRepository;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the store-assignment history of a company-scoped employee.
 *
 * <p>Every public method starts by resolving the company through {@link #resolveCompany(UUID)},
 * whose first statement is {@link CurrentUserContext#verifyCompanyAccess(UUID)} — the company-scope
 * contract. The employee is then loaded through a {@code companyId}-scoped query
 * ({@code findByIdAndCompanyId}), so a foreign or unknown employee is an
 * {@link EmployeeNotFoundException} (404) rather than a 200, and the assignment itself through an
 * employee-scoped query, so a foreign assignment is a {@link StoreAssignmentNotFoundException}.</p>
 *
 * <p><b>T8</b>: the store must belong to the employee's company, and that rule lives here because the
 * database cannot express a fact two hops away. {@link #resolveStore(UUID, UUID)} walks the same
 * {@code store -> zone -> region -> country -> company} chain the derivation walks and compares the
 * resolved company; a store that does not exist <b>or</b> belongs to another company is a
 * {@link CompanyStoreNotFoundException} (404). The rule is a decided invariant, not a provisional
 * mitigation — one company per person is the model's ceiling — so this check and its test are
 * load-bearing. The write path does deliberately <b>not</b> refuse a disabled store (T12): that
 * mirrors {@code StoreLocationService.resolveStore}, which does not filter the flag either, and the
 * row is harmless once the derivation ignores it.</p>
 *
 * <p><b>T4</b>: opening an assignment for the same {@code (employee, store)} closes the previous one
 * in the same transaction, exactly as {@code ContractService} closes the previous contract. The
 * predecessor is found with a plain JPQL query scoped to that store, so opening an assignment for a
 * <b>different</b> store closes nothing — that is how a second concurrent store is created (D1). If
 * a predecessor starts on the new start date, that is a 400, the only reachable form of "a new
 * assignment starting on or before the previous one's start date"; otherwise the predecessor's stored
 * bound is set to the successor's {@code validFrom} <b>verbatim</b> and flushed before the insert.</p>
 *
 * <p><b>D5</b>: the column's {@code valid_to} is <b>exclusive</b> (it is the first day NOT covered,
 * per the frozen {@code daterange(valid_from, valid_to, '[)')} of T2) while the <b>API is
 * inclusive</b> — {@code validTo}/{@code endDate} mean the last day covered, the way
 * {@code employees.termination_date} reads. This class is the only place the one-day arithmetic
 * lives, at the DTO boundary: {@link #toStoredEndDate(LocalDate)} converts a request's inclusive date
 * to the stored bound, and {@link #toApiEndDate(LocalDate)} converts it back for every response. T4
 * is stated in covered-day terms above, so storing the successor's {@code validFrom} verbatim as the
 * predecessor's exclusive bound makes the predecessor's last covered day {@code validFrom - 1} and
 * leaves no gap.</p>
 *
 * <p>An overlap the pre-check cannot see is deliberately <b>not</b> pre-checked and the
 * {@code DataIntegrityViolationException} is deliberately <b>not</b> caught: the partial exclusion
 * constraint {@code ex_employee_store_assignments_no_overlap} is the final authority (T2) and
 * {@code GlobalExceptionHandler} already maps its violation to 409. Catching it here to soften the
 * message would also mask the real rollback, since in PostgreSQL the first violation aborts the whole
 * transaction. There is no caching on these reads (T10): the derivation feeds an authorization input,
 * and a cache that outlives the decision is how {@code hr-org-structure}'s G12 happens. Nothing here
 * writes the token projection (T9) or touches the profile's store preference (T11, a separate
 * surface).</p>
 */
@Service
public class EmployeeStoreAssignmentService {

    private static final Logger logger = LoggerFactory.getLogger(EmployeeStoreAssignmentService.class);

    private final EmployeeStoreAssignmentRepository employeeStoreAssignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CurrentUserContext currentUserContext;

    public EmployeeStoreAssignmentService(
            EmployeeStoreAssignmentRepository employeeStoreAssignmentRepository,
            EmployeeRepository employeeRepository,
            CompanyRepository companyRepository,
            CompanyStoreRepository companyStoreRepository,
            CurrentUserContext currentUserContext) {
        this.employeeStoreAssignmentRepository = employeeStoreAssignmentRepository;
        this.employeeRepository = employeeRepository;
        this.companyRepository = companyRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Verifies company access and then loads the company, in that order.
     *
     * <p>The access check is deliberately the first statement: a caller without access to the company
     * is denied before any row is loaded. This mirrors {@code ContractService.resolveCompany}.</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws CompanyNotFoundException when the company does not exist
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
     * Loads a store and enforces T8: the store must resolve through its chain to the requested
     * company.
     *
     * <p>The walk is the same one the derivation performs — {@code store -> zone -> region -> country
     * -> company} — and a store that does not exist, or whose chain is broken, or whose company is
     * another company, resolves to the same {@link CompanyStoreNotFoundException} (404). Reporting it
     * as not-found rather than as a denial keeps the existence of another company's store
     * undisclosed, the convention the store services use for a caller-supplied id. A disabled store
     * is deliberately accepted: see the class javadoc (T12).</p>
     */
    private CompanyStore resolveStore(UUID companyId, UUID storeId) {
        var store =
                companyStoreRepository.findById(storeId).orElseThrow(() -> new CompanyStoreNotFoundException(storeId));
        var companyCountry = resolveCompanyCountry(store);
        if (companyCountry == null
                || companyCountry.getCompany() == null
                || !companyCountry.getCompany().getId().equals(companyId)) {
            throw new CompanyStoreNotFoundException(storeId);
        }
        return store;
    }

    /** The country hop of the chain, or {@code null} when the store has no zone or the zone no region. */
    private static CompanyCountry resolveCompanyCountry(CompanyStore store) {
        var zone = store.getCompanyZone();
        if (zone == null) {
            return null;
        }
        var region = zone.getCompanyRegion();
        return region == null ? null : region.getCompanyCountry();
    }

    /**
     * Returns the whole store-assignment history of the employee, newest first, with
     * {@code includeDisabled} deciding whether soft-deleted rows are in it.
     *
     * <p>The list is deliberately unpaginated: it is a person's record, and a closed or soft-deleted
     * assignment is part of it. Each row also carries its derived display chain (T14).</p>
     */
    @Transactional(readOnly = true)
    public List<StoreAssignmentResponse> getAssignments(UUID companyId, UUID employeeId, boolean includeDisabled) {
        resolveCompany(companyId);
        resolveEmployee(companyId, employeeId);
        return employeeStoreAssignmentRepository.findEmployeeAssignments(employeeId, includeDisabled).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Opens a new store assignment for the employee, closing the previous one for the same store the
     * day before (decision T4).
     *
     * <p>{@code validFrom} may be in the future: a future row is legal and simply derives nothing
     * until it covers today, so no date rule refuses it.</p>
     *
     * @throws CompanyStoreNotFoundException when the store does not belong to the company (T8)
     * @throws IllegalArgumentException when a previous assignment for the same store starts on the
     *     new start date
     */
    @Transactional
    public StoreAssignmentResponse createAssignment(UUID companyId, UUID employeeId, StoreAssignmentRequest request) {
        resolveCompany(companyId);
        var employee = resolveEmployee(companyId, employeeId);
        var store = resolveStore(companyId, request.companyStoreId());

        closePredecessor(employeeId, store.getId(), request.validFrom());

        var assignment = EmployeeStoreAssignment.builder()
                .employee(employee)
                .companyStore(store)
                .validFrom(request.validFrom())
                .enabled(true)
                .build();

        var saved = employeeStoreAssignmentRepository.save(assignment);
        logger.info(
                "Store assignment created: id={}, companyId={}, employeeId={}, companyStoreId={}, validFrom={}",
                saved.getId(),
                companyId,
                employeeId,
                store.getId(),
                saved.getValidFrom());

        return toResponse(saved);
    }

    /**
     * Closes an open store assignment once. Only an assignment whose {@code validTo} is {@code null}
     * can be closed; an already-closed one is a 409.
     *
     * <p>The requested end date defaults to today. A date before the assignment's {@code validFrom}
     * is a 400, which is also the intended outcome of closing a future assignment with no date.</p>
     *
     * @throws StoreAssignmentNotFoundException when the assignment does not belong to the employee
     * @throws ConflictException when the assignment is already closed
     * @throws IllegalArgumentException when the end date precedes the assignment's start date
     */
    @Transactional
    public StoreAssignmentResponse closeAssignment(
            UUID companyId, UUID employeeId, UUID id, CloseStoreAssignmentRequest request) {
        resolveCompany(companyId);
        resolveEmployee(companyId, employeeId);

        var assignment = employeeStoreAssignmentRepository
                .findByEmployeeIdAndId(employeeId, id)
                .orElseThrow(() -> new StoreAssignmentNotFoundException(id));

        if (assignment.getValidTo() != null) {
            throw new ConflictException("Store assignment " + id + " is already closed");
        }

        var endDate = (request != null && request.endDate() != null) ? request.endDate() : LocalDate.now();
        if (endDate.isBefore(assignment.getValidFrom())) {
            throw new IllegalArgumentException("endDate must be on or after validFrom");
        }

        // D5: the requested date is inclusive ("last day covered"), so the exclusive stored bound is
        // the day after it. Closing today therefore means today is covered.
        assignment.setValidTo(toStoredEndDate(endDate));
        var saved = employeeStoreAssignmentRepository.save(assignment);
        logger.info(
                "Store assignment closed: id={}, companyId={}, employeeId={}, endDate={}",
                id,
                companyId,
                employeeId,
                endDate);

        return toResponse(saved);
    }

    /**
     * Decision T4's pre-check: closes the employee's enabled assignment for this store covering the
     * new start date the day before it, and rejects a new start that lands on the previous
     * assignment's own start date.
     *
     * <p>The finder returns a {@code List} (see {@link EmployeeStoreAssignmentRepository}) even
     * though the partial exclusion constraint makes the result unique today; the empty list means "no
     * predecessor", and an unexpected extra row is closed too rather than becoming a 500. The finder's
     * coverage comparison is strict ({@code validTo > :date}) because the column is exclusive (D5): a
     * predecessor whose stored bound already equals the new start date does not cover it and is not
     * truncated.</p>
     *
     * <p>The predecessor is written with {@code saveAndFlush}, not {@code save}: Hibernate's action
     * queue executes INSERTs before UPDATEs within one flush, so a deferred close would let the new
     * open-ended row reach the database while the predecessor still covers the range, and the partial
     * exclusion constraint would refuse a write the close-the-previous rule had already made legal.
     * Flushing the close first is what keeps T4 and T2 consistent.</p>
     *
     * <p>The stored bound is the successor's {@code validFrom} <b>verbatim</b> (D5): under the
     * exclusive column that is T4's "the day before the new start date" in covered-day terms — the
     * predecessor's last covered day becomes {@code validFrom - 1} — and it leaves no gap.</p>
     */
    private void closePredecessor(UUID employeeId, UUID companyStoreId, LocalDate validFrom) {
        var predecessors = employeeStoreAssignmentRepository.findEnabledAssignmentForStoreCoveringDate(
                employeeId, companyStoreId, validFrom);
        for (var predecessor : predecessors) {
            if (predecessor.getValidFrom().equals(validFrom)) {
                throw new IllegalArgumentException(
                        "A store assignment cannot start on or before the previous assignment's start date");
            }
        }
        for (var predecessor : predecessors) {
            // D5: the exclusive bound IS the successor's validFrom; the predecessor's last covered day
            // is then validFrom - 1, with no gap and no overlap.
            predecessor.setValidTo(validFrom);
            employeeStoreAssignmentRepository.saveAndFlush(predecessor);
        }
    }

    private StoreAssignmentResponse toResponse(EmployeeStoreAssignment assignment) {
        var store = assignment.getCompanyStore();
        return new StoreAssignmentResponse(
                assignment.getId(),
                store.getId(),
                store.getStoreName(),
                assignment.getValidFrom(),
                toApiEndDate(assignment.getValidTo()),
                assignment.getEnabled(),
                toDerivedScope(store));
    }

    /**
     * D5, the DTO boundary in: the API's end date is the <b>last day covered</b> (inclusive) and the
     * column stores the <b>first day NOT covered</b> (exclusive), so the stored bound is one day after
     * the requested date. An open-ended assignment stays {@code null}. This and
     * {@link #toApiEndDate(LocalDate)} are the only places the one-day arithmetic lives.
     */
    private static LocalDate toStoredEndDate(LocalDate apiEndDate) {
        return apiEndDate == null ? null : apiEndDate.plusDays(1);
    }

    /** D5, the DTO boundary out: the exclusive stored bound is reported as the last day it covers. */
    private static LocalDate toApiEndDate(LocalDate storedEndDate) {
        return storedEndDate == null ? null : storedEndDate.minusDays(1);
    }

    /**
     * Maps the store's ancestor chain for display (T14), one hop at a time and <b>without</b> T12's
     * enabled filter: the operator must see the tree as it is, including that a disabled store grants
     * nothing.
     *
     * <p>The names come from the entities already loaded to walk the chain. A hop that does not
     * resolve omits that component and everything deeper — the chain is reported as far as it is
     * reachable — and is logged, never fetched with a second query.</p>
     */
    private DerivedStoreScope toDerivedScope(CompanyStore store) {
        if (store == null || store.getCompanyZone() == null) {
            logger.warn(
                    "Store assignment chain is incomplete: store {} has no zone", store == null ? null : store.getId());
            return new DerivedStoreScope(null, null, null, null, null, null, null, null);
        }
        var zone = store.getCompanyZone();
        if (zone.getCompanyRegion() == null) {
            logger.warn("Store assignment chain is incomplete: store {} has no region", store.getId());
            return new DerivedStoreScope(null, null, null, null, null, null, zone.getId(), zone.getZoneName());
        }
        var region = zone.getCompanyRegion();
        if (region.getCompanyCountry() == null) {
            logger.warn("Store assignment chain is incomplete: store {} has no country", store.getId());
            return new DerivedStoreScope(
                    null, null, null, null, region.getId(), region.getRegionName(), zone.getId(), zone.getZoneName());
        }
        var companyCountry = region.getCompanyCountry();
        var country = companyCountry.getCountry();
        var company = companyCountry.getCompany();
        if (country == null || company == null) {
            logger.warn(
                    "Store assignment chain is incomplete: company-country {} is missing its country or company",
                    companyCountry.getId());
        }
        return new DerivedStoreScope(
                company == null ? null : company.getId(),
                company == null ? null : company.getCompanyName(),
                companyCountry.getId(),
                country == null ? null : country.getCountryName(),
                region.getId(),
                region.getRegionName(),
                zone.getId(),
                zone.getZoneName());
    }
}
