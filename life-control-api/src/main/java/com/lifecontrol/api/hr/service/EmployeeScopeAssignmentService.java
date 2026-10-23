package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.exception.CompanyZoneNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.exception.ConflictException;
import com.lifecontrol.api.hr.dto.CloseScopeAssignmentRequest;
import com.lifecontrol.api.hr.dto.ScopeAssignmentRequest;
import com.lifecontrol.api.hr.dto.ScopeAssignmentResponse;
import com.lifecontrol.api.hr.dto.ScopeAssignmentResponse.DerivedScopeChain;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.exception.ScopeAssignmentNotFoundException;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.model.EmployeeScopeAssignment;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.repository.EmployeeScopeAssignmentRepository;
import com.lifecontrol.api.provisioning.service.AccessProvisioningProducer;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the per-level (country, region, zone) scope-assignment history of a
 * company-scoped employee.
 *
 * <p>Every public method starts by resolving the company through {@link #resolveCompany(UUID)}, whose
 * first statement is {@link CurrentUserContext#verifyCompanyAccess(UUID)} — the company-scope
 * contract. The employee is then loaded through a {@code companyId}-scoped query, so a foreign or
 * unknown employee is an {@link EmployeeNotFoundException} (404) rather than a 200, and the assignment
 * itself through an employee-scoped query, so a foreign assignment is a
 * {@link ScopeAssignmentNotFoundException}.</p>
 *
 * <p><b>The request must name exactly one node.</b> The level is the row's shape — one of
 * {@code companyCountryId}, {@code companyRegionId} or {@code companyZoneId} — and a request that
 * names none or several is an {@link IllegalArgumentException} (400) before anything is loaded, which
 * is the same rule the database restates as
 * {@code ck_employee_scope_assignments_one_node}.</p>
 *
 * <p><b>The node must belong to the employee's company</b>, and that rule lives here because the
 * database cannot express a fact one or two hops away (a region reaches its company through its
 * country, a zone through its region). {@link #resolveNode} walks the node's own chain and compares the
 * resolved company; a node that does not exist <b>or</b> belongs to another company is the level's own
 * not-found exception (404) — {@link CompanyCountryNotFoundException},
 * {@link CompanyRegionNotFoundException} or {@link CompanyZoneNotFoundException}. Reporting it as
 * not-found rather than as a denial keeps the existence of another company's node undisclosed, the
 * convention the store services use for a caller-supplied id. One company per person is the model's
 * ceiling, so this check is a decided invariant and not a provisional mitigation. The node's own
 * {@code enabled} flag is deliberately not consulted, exactly as {@code EmployeeStoreAssignmentService}
 * does not consult the store's: the row is harmless when the derivation is what filters.</p>
 *
 * <p><b>Opening an assignment for the same node closes the previous one</b> in the same transaction,
 * exactly as {@code EmployeeStoreAssignmentService} and {@code ContractService} close the previous
 * row. The predecessor is found with a query scoped to that node, so opening an assignment for a
 * <b>different</b> node closes nothing — that is how a second concurrent node is created. If a
 * predecessor starts on the new start date, that is a 400; otherwise its exclusive bound is set to the
 * successor's {@code validFrom} <b>verbatim</b> and flushed before the insert, so the predecessor's
 * last covered day is {@code validFrom - 1} with no gap and no overlap. Flushing matters: Hibernate's
 * action queue executes INSERTs before UPDATEs within one flush, so a deferred close would let the new
 * open-ended row reach the database while the predecessor still covers the range and the partial
 * exclusion constraint would refuse a write the close-the-previous rule had already made legal.</p>
 *
 * <p><b>The column's {@code valid_to} is exclusive</b> (the first day NOT covered, per the frozen
 * {@code daterange(valid_from, valid_to, '[)')}) while the <b>API is inclusive</b>:
 * {@code validTo}/{@code endDate} mean the last day covered, the way {@code employees.termination_date}
 * reads. This class is the only place the one-day arithmetic lives, at the DTO boundary:
 * {@link #toStoredEndDate(LocalDate)} converts a request's inclusive date to the stored bound, and
 * {@link #toApiEndDate(LocalDate)} converts it back for every response.</p>
 *
 * <p>An overlap the pre-check cannot see is deliberately <b>not</b> pre-checked and the
 * {@code DataIntegrityViolationException} is deliberately <b>not</b> caught: the three partial
 * exclusion constraints are the final authority and {@code GlobalExceptionHandler} already maps their
 * violation to 409. Catching it here to soften the message would also mask the real rollback, since in
 * PostgreSQL the first violation aborts the whole transaction. There is no caching on these reads: the
 * derivation feeds an authorization input. Each successful write calls
 * {@link AccessProvisioningProducer#onMembershipChanged(UUID, Employee)}, the same hook the store
 * assignment calls, so the five claims converge without a second write path.</p>
 *
 * <p>There is deliberately <b>no</b> employee-status guard here: the producer already refuses a
 * revoked employee, and the read path derives nothing for one, so a guard would be a second copy of
 * the same rule. There is deliberately no {@code PUT} and no {@code DELETE} either: a move is a new
 * assignment and a mistake is closed, because rewriting a row would erase when someone stopped being
 * assigned somewhere.</p>
 */
@Service
public class EmployeeScopeAssignmentService {

    private static final Logger logger = LoggerFactory.getLogger(EmployeeScopeAssignmentService.class);

    private final EmployeeScopeAssignmentRepository employeeScopeAssignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final CompanyCountryRepository companyCountryRepository;
    private final CompanyRegionRepository companyRegionRepository;
    private final CompanyZoneRepository companyZoneRepository;
    private final CurrentUserContext currentUserContext;
    private final AccessProvisioningProducer accessProvisioningProducer;

    public EmployeeScopeAssignmentService(
            EmployeeScopeAssignmentRepository employeeScopeAssignmentRepository,
            EmployeeRepository employeeRepository,
            CompanyRepository companyRepository,
            CompanyCountryRepository companyCountryRepository,
            CompanyRegionRepository companyRegionRepository,
            CompanyZoneRepository companyZoneRepository,
            CurrentUserContext currentUserContext,
            AccessProvisioningProducer accessProvisioningProducer) {
        this.employeeScopeAssignmentRepository = employeeScopeAssignmentRepository;
        this.employeeRepository = employeeRepository;
        this.companyRepository = companyRepository;
        this.companyCountryRepository = companyCountryRepository;
        this.companyRegionRepository = companyRegionRepository;
        this.companyZoneRepository = companyZoneRepository;
        this.currentUserContext = currentUserContext;
        this.accessProvisioningProducer = accessProvisioningProducer;
    }

    /**
     * Verifies company access and then loads the company, in that order: a caller without access to
     * the company is denied before any row is loaded.
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
     * Returns the whole scope-assignment history of the employee, newest first, with
     * {@code includeDisabled} deciding whether soft-deleted rows are in it.
     *
     * <p>The list is deliberately unpaginated: it is a person's record, and a closed or soft-deleted
     * assignment is part of it. Each row also carries its level, its node name and its derived
     * display chain.</p>
     */
    @Transactional(readOnly = true)
    public List<ScopeAssignmentResponse> getAssignments(UUID companyId, UUID employeeId, boolean includeDisabled) {
        resolveCompany(companyId);
        resolveEmployee(companyId, employeeId);
        return employeeScopeAssignmentRepository.findEmployeeAssignments(employeeId, includeDisabled).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Opens a new scope assignment for the employee, closing the previous one for the same node the
     * day before.
     *
     * <p>{@code validFrom} may be in the future: a future row is legal and simply derives nothing
     * until it covers today, so no date rule refuses it.</p>
     *
     * @throws IllegalArgumentException when the request does not name exactly one node, or when a
     *     previous assignment for the same node starts on the new start date
     * @throws CompanyCountryNotFoundException when the country does not belong to the company
     * @throws CompanyRegionNotFoundException when the region does not belong to the company
     * @throws CompanyZoneNotFoundException when the zone does not belong to the company
     */
    @Transactional
    public ScopeAssignmentResponse createAssignment(UUID companyId, UUID employeeId, ScopeAssignmentRequest request) {
        resolveCompany(companyId);
        var employee = resolveEmployee(companyId, employeeId);
        var node = resolveNode(companyId, request);

        closePredecessor(employeeId, node.nodeId(), request.validFrom());

        var assignment = EmployeeScopeAssignment.builder()
                .employee(employee)
                .companyCountry(node.companyCountry())
                .companyRegion(node.companyRegion())
                .companyZone(node.companyZone())
                .validFrom(request.validFrom())
                .enabled(true)
                .build();

        var saved = employeeScopeAssignmentRepository.save(assignment);
        logger.info(
                "Scope assignment created: id={}, companyId={}, employeeId={}, level={}, nodeId={}, validFrom={}",
                saved.getId(),
                companyId,
                employeeId,
                node.level(),
                node.nodeId(),
                saved.getValidFrom());

        accessProvisioningProducer.onMembershipChanged(companyId, employee);

        return toResponse(saved);
    }

    /**
     * Closes an open scope assignment once. Only an assignment whose {@code validTo} is {@code null}
     * can be closed; an already-closed one is a 409.
     *
     * <p>The requested end date defaults to today. A date before the assignment's {@code validFrom} is
     * a 400, which is also the intended outcome of closing a future assignment with no date.</p>
     *
     * @throws ScopeAssignmentNotFoundException when the assignment does not belong to the employee
     * @throws ConflictException when the assignment is already closed
     * @throws IllegalArgumentException when the end date precedes the assignment's start date
     */
    @Transactional
    public ScopeAssignmentResponse closeAssignment(
            UUID companyId, UUID employeeId, UUID id, CloseScopeAssignmentRequest request) {
        resolveCompany(companyId);
        var employee = resolveEmployee(companyId, employeeId);

        var assignment = employeeScopeAssignmentRepository
                .findByEmployeeIdAndId(employeeId, id)
                .orElseThrow(() -> new ScopeAssignmentNotFoundException(id));

        if (assignment.getValidTo() != null) {
            throw new ConflictException("Scope assignment " + id + " is already closed");
        }

        var endDate = (request != null && request.endDate() != null) ? request.endDate() : LocalDate.now();
        if (endDate.isBefore(assignment.getValidFrom())) {
            throw new IllegalArgumentException("endDate must be on or after validFrom");
        }

        // The requested date is inclusive ("last day covered"), so the exclusive stored bound is the
        // day after it. Closing today therefore means today is covered.
        assignment.setValidTo(toStoredEndDate(endDate));
        var saved = employeeScopeAssignmentRepository.save(assignment);
        logger.info(
                "Scope assignment closed: id={}, companyId={}, employeeId={}, endDate={}",
                id,
                companyId,
                employeeId,
                endDate);

        accessProvisioningProducer.onMembershipChanged(companyId, employee);

        return toResponse(saved);
    }

    /**
     * Resolves the one node the request names and enforces that it belongs to the employee's company.
     *
     * <p>The exactly-one check comes first and writes nothing: a request with no node or with more than
     * one is a 400 whose message names the three fields, before any repository is touched.</p>
     */
    private ResolvedNode resolveNode(UUID companyId, ScopeAssignmentRequest request) {
        var nodeCount = 0;
        if (request.companyCountryId() != null) {
            nodeCount++;
        }
        if (request.companyRegionId() != null) {
            nodeCount++;
        }
        if (request.companyZoneId() != null) {
            nodeCount++;
        }
        if (nodeCount != 1) {
            throw new IllegalArgumentException(
                    "A scope assignment must name exactly one node: companyCountryId, companyRegionId or"
                            + " companyZoneId");
        }

        if (request.companyCountryId() != null) {
            var countryId = request.companyCountryId();
            var companyCountry = companyCountryRepository
                    .findById(countryId)
                    .orElseThrow(() -> new CompanyCountryNotFoundException(countryId));
            if (companyCountry.getCompany() == null
                    || !companyCountry.getCompany().getId().equals(companyId)) {
                throw new CompanyCountryNotFoundException(countryId);
            }
            return new ResolvedNode(ScopeLevel.COUNTRY, countryId, companyCountry, null, null);
        }

        if (request.companyRegionId() != null) {
            var regionId = request.companyRegionId();
            var region = companyRegionRepository
                    .findById(regionId)
                    .orElseThrow(
                            () -> new CompanyRegionNotFoundException("Company region not found with id: " + regionId));
            var companyCountry = region.getCompanyCountry();
            if (companyCountry == null
                    || companyCountry.getCompany() == null
                    || !companyCountry.getCompany().getId().equals(companyId)) {
                throw new CompanyRegionNotFoundException("Company region not found with id: " + regionId);
            }
            return new ResolvedNode(ScopeLevel.REGION, regionId, null, region, null);
        }

        var zoneId = request.companyZoneId();
        var zone = companyZoneRepository
                .findById(zoneId)
                .orElseThrow(() -> new CompanyZoneNotFoundException("Company zone not found with id: " + zoneId));
        var companyRegion = zone.getCompanyRegion();
        var companyCountry = companyRegion == null ? null : companyRegion.getCompanyCountry();
        if (companyCountry == null
                || companyCountry.getCompany() == null
                || !companyCountry.getCompany().getId().equals(companyId)) {
            throw new CompanyZoneNotFoundException("Company zone not found with id: " + zoneId);
        }
        return new ResolvedNode(ScopeLevel.ZONE, zoneId, null, null, zone);
    }

    /**
     * Closes the employee's enabled assignment for this node covering the new start date the day
     * before it, and rejects a new start that lands on the previous assignment's own start date.
     *
     * <p>The finder returns a {@code List} even though the exclusion constraint makes the result unique
     * today; the empty list means "no predecessor", and an unexpected extra row is closed too rather
     * than becoming a 500. The stored bound is the successor's {@code validFrom} <b>verbatim</b>, which
     * under the exclusive column is "the day before the new start date" in covered-day terms. The
     * predecessor is written with {@code saveAndFlush} so the UPDATE reaches the database before the
     * new INSERT.</p>
     */
    private void closePredecessor(UUID employeeId, UUID nodeId, LocalDate validFrom) {
        var predecessors = employeeScopeAssignmentRepository.findEnabledAssignmentForNodeCoveringDate(
                employeeId, nodeId, validFrom);
        for (var predecessor : predecessors) {
            if (predecessor.getValidFrom().equals(validFrom)) {
                throw new IllegalArgumentException(
                        "A scope assignment cannot start on or before the previous assignment's start date");
            }
        }
        for (var predecessor : predecessors) {
            predecessor.setValidTo(validFrom);
            employeeScopeAssignmentRepository.saveAndFlush(predecessor);
        }
    }

    private ScopeAssignmentResponse toResponse(EmployeeScopeAssignment assignment) {
        var level = levelOf(assignment);
        return new ScopeAssignmentResponse(
                assignment.getId(),
                level,
                assignment.getCompanyCountry() == null
                        ? null
                        : assignment.getCompanyCountry().getId(),
                assignment.getCompanyRegion() == null
                        ? null
                        : assignment.getCompanyRegion().getId(),
                assignment.getCompanyZone() == null
                        ? null
                        : assignment.getCompanyZone().getId(),
                nodeName(assignment),
                assignment.getValidFrom(),
                toApiEndDate(assignment.getValidTo()),
                assignment.getEnabled(),
                toDerivedChain(assignment));
    }

    /**
     * The level of a stored row, read from its shape. The database's
     * {@code ck_employee_scope_assignments_one_node} guarantees exactly one node column is set, so the
     * three checks are unambiguous; a row that somehow set none is reported as {@code COUNTRY} and its
     * chain mapper then reports it as unresolvable rather than inventing a level.
     */
    private static ScopeLevel levelOf(EmployeeScopeAssignment assignment) {
        if (assignment.getCompanyZone() != null) {
            return ScopeLevel.ZONE;
        }
        if (assignment.getCompanyRegion() != null) {
            return ScopeLevel.REGION;
        }
        return ScopeLevel.COUNTRY;
    }

    /**
     * The display name of the assigned node: the country's name for a country row (the
     * {@code company_countries} row has no name of its own), the region's or the zone's own name
     * otherwise. A country row whose catalog entry does not resolve reports no name and is logged, never
     * fetched with a second query.
     */
    private String nodeName(EmployeeScopeAssignment assignment) {
        if (assignment.getCompanyZone() != null) {
            return assignment.getCompanyZone().getZoneName();
        }
        if (assignment.getCompanyRegion() != null) {
            return assignment.getCompanyRegion().getRegionName();
        }
        var companyCountry = assignment.getCompanyCountry();
        if (companyCountry == null || companyCountry.getCountry() == null) {
            logger.warn("Scope assignment {} has no resolvable country name", assignment.getId());
            return null;
        }
        return companyCountry.getCountry().getCountryName();
    }

    /**
     * Maps the row's ancestor chain for display, one hop at a time and <b>without</b> any enabled
     * filter: the operator must see the tree as it is.
     *
     * <p>The names come from the entities already loaded to walk the chain. A hop that does not resolve
     * omits that component and everything deeper — the chain is reported as far as it is reachable —
     * and is logged, never fetched with a second query.</p>
     */
    private DerivedScopeChain toDerivedChain(EmployeeScopeAssignment assignment) {
        var zone = assignment.getCompanyZone();
        var region = zone != null ? zone.getCompanyRegion() : assignment.getCompanyRegion();
        var companyCountry = region != null ? region.getCompanyCountry() : assignment.getCompanyCountry();
        if (companyCountry == null) {
            logger.warn("Scope assignment chain is incomplete: assignment {} has no country", assignment.getId());
            return new DerivedScopeChain(
                    null,
                    null,
                    null,
                    null,
                    region == null ? null : region.getId(),
                    region == null ? null : region.getRegionName(),
                    zone == null ? null : zone.getId(),
                    zone == null ? null : zone.getZoneName());
        }
        var company = companyCountry.getCompany();
        var country = companyCountry.getCountry();
        if (country == null || company == null) {
            logger.warn(
                    "Scope assignment chain is incomplete: company-country {} is missing its country or company",
                    companyCountry.getId());
        }
        return new DerivedScopeChain(
                company == null ? null : company.getId(),
                company == null ? null : company.getCompanyName(),
                companyCountry.getId(),
                country == null ? null : country.getCountryName(),
                region == null ? null : region.getId(),
                region == null ? null : region.getRegionName(),
                zone == null ? null : zone.getId(),
                zone == null ? null : zone.getZoneName());
    }

    /**
     * The DTO boundary in: the API's end date is the <b>last day covered</b> (inclusive) and the column
     * stores the <b>first day NOT covered</b> (exclusive), so the stored bound is one day after the
     * requested date. An open-ended assignment stays {@code null}.
     */
    private static LocalDate toStoredEndDate(LocalDate apiEndDate) {
        return apiEndDate == null ? null : apiEndDate.plusDays(1);
    }

    /** The DTO boundary out: the exclusive stored bound is reported as the last day it covers. */
    private static LocalDate toApiEndDate(LocalDate storedEndDate) {
        return storedEndDate == null ? null : storedEndDate.minusDays(1);
    }

    /** The one node a request names, with its level and the id used by the close-the-previous finder. */
    private record ResolvedNode(
            ScopeLevel level,
            UUID nodeId,
            CompanyCountry companyCountry,
            CompanyRegion companyRegion,
            CompanyZone companyZone) {}
}
