package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.service.EmployeeStatuses;
import com.lifecontrol.api.provisioning.exception.TerminatedEmployeeRefusedException;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <b>explicit request surface</b> of the employee-access projection (unit W5c, slice S1): the two
 * intents an operator asks for over HTTP, {@link #apply(UUID, UUID)} and
 * {@link #deactivate(UUID, UUID)}.
 *
 * <p><b>It asks; it does not decide and it does not converge.</b> Both methods end in
 * {@link AccessProvisioningGateService#request(UUID, UUID, AccessProvisioningTaskKind, String)}, so
 * the initial status is the approval rule O2 derives (a task for the store-scoped set applies itself,
 * everything else waits for a second pair of eyes), the reviewed diff is frozen by the gate, and the
 * convergence itself belongs to the worker. Nothing here writes a role, an account or a claim.</p>
 *
 * <p><b>The actor is the JWT {@code sub} claim</b> — {@link CurrentUserContext#getUserId()} and never
 * {@link CurrentUserContext#getUsername()} (record T83): the persisted {@code requested_by} column is
 * {@code VARCHAR(36)} and rule O3 compares it against the deciding actor, so both sides have to use
 * the same basis or the comparison silently never matches.</p>
 *
 * <p><b>Two refusals precede the task, and their order is the rule.</b> An employee whose employment
 * status is {@code Terminated} is refused first (record T84): the {@code ACTIVATE} kind's account
 * half would create or link the account and send the invitation, which is the opposite of what a
 * revocation is for. Then the deliverable-domain refusal of record T68, taken from
 * {@link AccessProvisioningAccountService#requireDeliverableCompanyEmailDomain(Employee)} — the single
 * definition of that rule — so a company whose generated addresses cannot receive mail sees which path
 * applies instead of discovering it from a bounce. Both happen <b>before</b> any task row exists.</p>
 *
 * <p><b>{@code deactivate} carries neither check, and that is deliberate.</b> Revoking a person who is
 * already revoked is what the intent is for, so it does not consult the status; and a revocation sends
 * no invitation, so it does not consult the email domain at all. It re-derives the diff like every
 * request does, and a {@code Terminated} employee's required set is empty — which is exactly the
 * convergence O4 asks for.</p>
 *
 * <p><b>The company check is the first statement of both methods</b>, and the employee is loaded
 * through the company-scoped {@code findByIdAndCompanyId}, so a foreign or unknown employee is an
 * {@link EmployeeNotFoundException} (404) instead of an intent recorded against somebody else's
 * employee.</p>
 */
@Service
public class AccessProvisioningRequestService {

    private final CurrentUserContext currentUserContext;
    private final EmployeeRepository employeeRepository;
    private final AccessProvisioningAccountService accountService;
    private final AccessProvisioningGateService gateService;

    public AccessProvisioningRequestService(
            CurrentUserContext currentUserContext,
            EmployeeRepository employeeRepository,
            AccessProvisioningAccountService accountService,
            AccessProvisioningGateService gateService) {
        this.currentUserContext = currentUserContext;
        this.employeeRepository = employeeRepository;
        this.accountService = accountService;
        this.gateService = gateService;
    }

    /**
     * Requests the projection for an employee: an {@code ACTIVATE} task that will create or link the
     * account and converge its roles and claim attributes.
     *
     * @param companyId the company the caller claims and the employee must belong to
     * @param employeeId the employee to provision
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws EmployeeNotFoundException when the employee does not belong to the company
     * @throws TerminatedEmployeeRefusedException when the employee's status is {@code Terminated}
     * @throws com.lifecontrol.api.provisioning.exception.InvitationNotDeliverableException when the
     *     company has no usable email domain
     * @throws com.lifecontrol.api.provisioning.exception.AccessProvisioningTaskAlreadyOpenException
     *     when the employee already has an open task
     */
    @Transactional
    public void apply(UUID companyId, UUID employeeId) {
        currentUserContext.verifyCompanyAccess(companyId);
        var employee = requireEmployeeInCompany(companyId, employeeId);

        if (EmployeeStatuses.isTerminated(employee)) {
            throw new TerminatedEmployeeRefusedException(employeeId);
        }
        accountService.requireDeliverableCompanyEmailDomain(employee);

        gateService.request(companyId, employeeId, AccessProvisioningTaskKind.ACTIVATE, currentUserContext.getUserId());
    }

    /**
     * Requests the revocation of an employee's access: a {@code DEACTIVATE} task that disables the
     * account and then converges the roles and the claim attributes against the current truth.
     *
     * @param companyId the company the caller claims and the employee must belong to
     * @param employeeId the employee to revoke
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws EmployeeNotFoundException when the employee does not belong to the company
     * @throws com.lifecontrol.api.provisioning.exception.AccessProvisioningTaskAlreadyOpenException
     *     when the employee already has an open task
     */
    @Transactional
    public void deactivate(UUID companyId, UUID employeeId) {
        currentUserContext.verifyCompanyAccess(companyId);
        requireEmployeeInCompany(companyId, employeeId);

        gateService.request(
                companyId, employeeId, AccessProvisioningTaskKind.DEACTIVATE, currentUserContext.getUserId());
    }

    /**
     * Loads the employee through the company-scoped finder, so a foreign or unknown employee is a 404
     * before anything is written — the same scope {@link AccessProvisioningGateService} and
     * {@link AccessProvisioningQueryService} apply.
     */
    private Employee requireEmployeeInCompany(UUID companyId, UUID employeeId) {
        return employeeRepository
                .findByIdAndCompanyId(employeeId, companyId)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeId));
    }
}
