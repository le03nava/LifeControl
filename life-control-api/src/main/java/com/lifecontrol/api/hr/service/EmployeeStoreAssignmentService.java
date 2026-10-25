package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.hr.dto.CloseScopeAssignmentRequest;
import com.lifecontrol.api.hr.dto.CloseStoreAssignmentRequest;
import com.lifecontrol.api.hr.dto.ScopeAssignmentRequest;
import com.lifecontrol.api.hr.dto.ScopeAssignmentResponse;
import com.lifecontrol.api.hr.dto.StoreAssignmentRequest;
import com.lifecontrol.api.hr.dto.StoreAssignmentResponse;
import com.lifecontrol.api.hr.dto.StoreAssignmentResponse.DerivedStoreScope;
import com.lifecontrol.api.hr.exception.StoreAssignmentNotFoundException;
import com.lifecontrol.api.hr.repository.EmployeeScopeAssignmentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Compatibility façade</b> of the pre-merge {@code /store-assignments} surface over the per-level
 * scope table, and nothing else. A later task deletes it together with its controller and DTOs once
 * the app no longer calls the route.
 *
 * <p>{@code V32__employee_scope_assignments_store_level.sql} made the store the fourth node column of
 * {@code employee_scope_assignments}, and since then only that table feeds the five {@code company_*}
 * claims. A write that still landed in {@code employee_store_assignments} would therefore be accepted
 * and silently grant nothing, which is the worst failure this surface can have for its callers, so
 * this class does not read or write the old table at all: every operation delegates to
 * {@link EmployeeScopeAssignmentService} and maps the answer back to {@link StoreAssignmentResponse}.
 * Its public API and that payload are kept <b>byte-identical</b> on purpose, so
 * {@code EmployeeStoreAssignmentController} and {@code ProfileService} need no change and the branch
 * stays deployable at every commit (S8, S10).</p>
 *
 * <p><b>The filter is this class's own rule.</b> The scope service answers about all four levels, so
 * the store read keeps the rows whose level is {@link ScopeLevel#STORE} and drops the rest: a store
 * route that reported a country row as a store would show the operator a resource its screen cannot
 * act on. For the same reason the close path refuses a non-store row with the route's own
 * {@link StoreAssignmentNotFoundException} (404) instead of delegating it — a store screen must not be
 * able to mutate a level it does not render.</p>
 *
 * <p><b>What this class deliberately does not do.</b> It performs no
 * {@link com.lifecontrol.api.common.auth.CurrentUserContext#verifyCompanyAccess(UUID)} itself and
 * owns no transaction of its own beyond the delegation: the company-scope check, the employee-scope
 * check, the exactly-one-node rule, the same-company node rule, the close-the-previous rule, the
 * inclusive-to-exclusive date arithmetic and the {@code AccessProvisioningProducer} hook all live in
 * {@link EmployeeScopeAssignmentService}, which is the one place they must not be duplicated. There is
 * no logging here either: every write that matters is logged where it happens.</p>
 *
 * <p>{@code getCurrentAssignmentsForEmployee} is the one read with no company scope, and it stays that
 * way (S10): its caller, {@code ProfileService}, resolves the employee from the authenticated
 * principal's own {@code keycloak_user_id} and then asks for that employee's own rows, so demanding a
 * company claim would refuse the very caller the profile screen serves.</p>
 */
@Service
public class EmployeeStoreAssignmentService {

    private final EmployeeScopeAssignmentService employeeScopeAssignmentService;
    private final EmployeeScopeAssignmentRepository employeeScopeAssignmentRepository;

    public EmployeeStoreAssignmentService(
            EmployeeScopeAssignmentService employeeScopeAssignmentService,
            EmployeeScopeAssignmentRepository employeeScopeAssignmentRepository) {
        this.employeeScopeAssignmentService = employeeScopeAssignmentService;
        this.employeeScopeAssignmentRepository = employeeScopeAssignmentRepository;
    }

    /**
     * Returns the {@code STORE} rows of the employee's assignment history, newest first, with
     * {@code includeDisabled} deciding whether soft-deleted rows are in it.
     *
     * <p>The company scope and the employee scope are enforced by the delegation, in that order, so a
     * caller without access to the company is denied before any row is loaded and a foreign employee
     * is a 404 rather than a 200.</p>
     */
    @Transactional(readOnly = true)
    public List<StoreAssignmentResponse> getAssignments(UUID companyId, UUID employeeId, boolean includeDisabled) {
        return toStoreResponses(employeeScopeAssignmentService.getAssignments(companyId, employeeId, includeDisabled));
    }

    /**
     * The store assignments covering <b>today</b> for one employee, as the profile path reads them.
     *
     * <p>Deliberately <b>scope-free</b>, exactly as it was before the merge: its only caller resolves
     * the employee from the authenticated principal's own {@code keycloak_user_id} and then asks for
     * that employee's own rows, so this is a self-scoped read by construction. The covering-date
     * finder and its deterministic order are the derivation's own, so the profile's preference and the
     * token can never disagree about which stores are current.</p>
     */
    @Transactional(readOnly = true)
    public List<StoreAssignmentResponse> getCurrentAssignmentsForEmployee(UUID employeeId) {
        return toStoreResponses(employeeScopeAssignmentService.getCurrentStoreAssignments(employeeId));
    }

    /**
     * Opens a new store assignment for the employee, closing the previous one for the same store the
     * day before — both rules are the scope service's, applied to a request that names only the store.
     *
     * <p>{@code validFrom} may be in the future: a future row is legal and simply derives nothing
     * until it covers today, so no date rule refuses it.</p>
     */
    @Transactional
    public StoreAssignmentResponse createAssignment(UUID companyId, UUID employeeId, StoreAssignmentRequest request) {
        var scopeRequest = new ScopeAssignmentRequest(null, null, null, request.companyStoreId(), request.validFrom());
        return toStoreResponse(employeeScopeAssignmentService.createAssignment(companyId, employeeId, scopeRequest));
    }

    /**
     * Closes an open store assignment once, delegating the close itself to the scope service.
     *
     * <p>The row is resolved first through the <b>employee-scoped</b> lookup, and a row that does not
     * belong to the employee, or that assigns another level, is the route's own
     * {@link StoreAssignmentNotFoundException} (404) — closing a country row by its id through the
     * store route is the shape this check exists to refuse. Everything else (already closed as a 409,
     * an end date before {@code validFrom} as a 400, the default-to-today and the
     * inclusive-to-exclusive conversion) is the scope service's, unchanged.</p>
     */
    @Transactional
    public StoreAssignmentResponse closeAssignment(
            UUID companyId, UUID employeeId, UUID id, CloseStoreAssignmentRequest request) {
        employeeScopeAssignmentRepository
                .findByEmployeeIdAndId(employeeId, id)
                .filter(assignment -> assignment.getCompanyStore() != null)
                .orElseThrow(() -> new StoreAssignmentNotFoundException(id));

        var closeRequest = request == null ? null : new CloseScopeAssignmentRequest(request.endDate());
        return toStoreResponse(employeeScopeAssignmentService.closeAssignment(companyId, employeeId, id, closeRequest));
    }

    /** The store rows of a scope answer, mapped and with every other level dropped. */
    private static List<StoreAssignmentResponse> toStoreResponses(List<ScopeAssignmentResponse> responses) {
        return responses.stream()
                .filter(response -> response.level() == ScopeLevel.STORE)
                .map(EmployeeStoreAssignmentService::toStoreResponse)
                .toList();
    }

    /**
     * Maps one scope row to the payload this route has always returned.
     *
     * <p>The mapping is mechanical and total: a {@code STORE} row's {@code nodeName} is the store's
     * name, and {@link ScopeAssignmentResponse.DerivedScopeChain} carries the same eight components in
     * the same order as {@link DerivedStoreScope}, so no value is re-derived here and no second read is
     * issued.</p>
     */
    private static StoreAssignmentResponse toStoreResponse(ScopeAssignmentResponse response) {
        var derived = response.derived();
        return new StoreAssignmentResponse(
                response.id(),
                response.companyStoreId(),
                response.nodeName(),
                response.validFrom(),
                response.validTo(),
                response.enabled(),
                new DerivedStoreScope(
                        derived.companyId(),
                        derived.companyName(),
                        derived.companyCountryId(),
                        derived.companyCountryName(),
                        derived.companyRegionId(),
                        derived.companyRegionName(),
                        derived.companyZoneId(),
                        derived.companyZoneName()));
    }
}
