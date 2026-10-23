package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.service.EmployeeStatuses;
import com.lifecontrol.api.provisioning.exception.NoOpenAccessProvisioningTaskException;
import com.lifecontrol.api.provisioning.exception.RevokedEmployeeRefusedException;
import com.lifecontrol.api.provisioning.exception.RevokedEmployeeRequiredException;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
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
 * <p><b>One refusal precedes the task, and it is the rule.</b> An employee whose employment
 * status is {@code Terminated} is refused first (record T84): the {@code ACTIVATE} kind's account
 * half would create or link the account and send the invitation, which is the opposite of what a
 * revocation is for, and it happens <b>before</b> any task row exists. The deliverable-domain
 * refusal of record T68 is <b>deleted</b> (records T102/T104): the invitation goes to the address the
 * account carries, so the field the company declares was never the fact that decided
 * deliverability.</p>
 *
 * <p><b>{@code deactivate} carries exactly one check, and it is the inverse of {@code apply}'s.</b>
 * Revocation requires a {@code Terminated} employee (record T101): the intent removes the template's
 * roles and the five claims, while the required set is derived from the current contract — so for an
 * employee who is not revoked the next fact of that same employee would grant back exactly what the
 * revocation removed, and the intent would undo itself. What it still does not do is consult the email
 * domain: a revocation sends no invitation, and a {@code Terminated} employee's required set is empty,
 * which is exactly the convergence O4 asks for.</p>
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
    private final AccessProvisioningTaskService taskService;
    private final AccessProvisioningTaskRepository taskRepository;

    public AccessProvisioningRequestService(
            CurrentUserContext currentUserContext,
            EmployeeRepository employeeRepository,
            AccessProvisioningAccountService accountService,
            AccessProvisioningGateService gateService,
            AccessProvisioningTaskService taskService,
            AccessProvisioningTaskRepository taskRepository) {
        this.currentUserContext = currentUserContext;
        this.employeeRepository = employeeRepository;
        this.accountService = accountService;
        this.gateService = gateService;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
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
     * @throws RevokedEmployeeRefusedException when the employee's status is {@code Terminated}
     * @throws com.lifecontrol.api.provisioning.exception.AccessProvisioningTaskAlreadyOpenException
     *     when the employee already has an open task
     */
    @Transactional
    public void apply(UUID companyId, UUID employeeId) {
        currentUserContext.verifyCompanyAccess(companyId);
        var employee = requireEmployeeInCompany(companyId, employeeId);

        if (EmployeeStatuses.isTerminated(employee)) {
            throw new RevokedEmployeeRefusedException(employeeId);
        }
        gateService.request(companyId, employeeId, AccessProvisioningTaskKind.ACTIVATE, currentUserContext.getUserId());
    }

    /**
     * Requests the revocation of an employee's access: a {@code DEACTIVATE} task that disables the
     * account and then converges the roles and the claim attributes against the current truth.
     *
     * <p><b>It requires a {@code Terminated} employee</b> (record T101). A revocation removes the
     * template's roles and the five claims, while the required set is derived from the current contract
     * — so for an employee who is not revoked the next fact of that same employee would grant back
     * exactly what the revocation removed, and the intent would undo itself. The refusal is a named
     * {@link RevokedEmployeeRequiredException} — 409 through the conflict category, record T86 — and
     * it happens <b>before</b> any task row exists, the same shape {@link #apply(UUID, UUID)} applies in
     * the opposite direction (record T84).</p>
     *
     * <p>It still does <b>not</b> consult the email domain: a revocation sends no invitation, so the
     * deliverability rule has nothing to decide here.</p>
     *
     * @param companyId the company the caller claims and the employee must belong to
     * @param employeeId the employee to revoke
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws EmployeeNotFoundException when the employee does not belong to the company
     * @throws RevokedEmployeeRequiredException when the employee is not {@code Terminated}
     * @throws com.lifecontrol.api.provisioning.exception.AccessProvisioningTaskAlreadyOpenException
     *     when the employee already has an open task
     */
    @Transactional
    public void deactivate(UUID companyId, UUID employeeId) {
        currentUserContext.verifyCompanyAccess(companyId);
        var employee = requireEmployeeInCompany(companyId, employeeId);

        if (!EmployeeStatuses.isTerminated(employee)) {
            throw new RevokedEmployeeRequiredException(employeeId);
        }

        gateService.request(
                companyId, employeeId, AccessProvisioningTaskKind.DEACTIVATE, currentUserContext.getUserId());
    }

    /**
     * Returns the employee's failed access request to the queue, due immediately (record T87).
     *
     * <p><b>The route carries no {@code taskId}, and that is the surface's own shape</b>: the row is
     * resolved from the employee against the open set the creation path already owns
     * ({@link AccessProvisioningTaskService#OPEN_STATUSES}), so "open" has one definition instead of
     * a second literal that would drift from the partial index's predicate. No open task is a named
     * 404 ({@link NoOpenAccessProvisioningTaskException}); an open task whose status cannot be retried
     * is the state machine's own refusal, raised under its pessimistic lock and propagated unchanged,
     * so this class never re-states {@code FAILED → PENDING}.</p>
     *
     * <p>A retry is deliberately <b>not</b> gated: the task already passed the gate (or never needed
     * it), and re-queueing an intent that exists cannot widen what it will apply — the diff is
     * re-derived at apply time (records T11/T67).</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws EmployeeNotFoundException when the employee does not belong to the company
     * @throws NoOpenAccessProvisioningTaskException when the employee has no open task
     * @throws com.lifecontrol.api.provisioning.exception.InvalidTaskStatusTransitionException when the
     *     open task is not {@code FAILED}
     */
    @Transactional
    public void retry(UUID companyId, UUID employeeId) {
        currentUserContext.verifyCompanyAccess(companyId);
        requireEmployeeInCompany(companyId, employeeId);

        var open = taskRepository
                .findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                        employeeId, AccessProvisioningTaskService.OPEN_STATUSES)
                .orElseThrow(() -> new NoOpenAccessProvisioningTaskException(employeeId));

        taskService.retry(employeeId, open.getId());
    }

    /**
     * Re-sends the activation invitation (record T69): the consequence O1 accepted when it chose the
     * emailed link — the link expires, so the screen needs a way to send another one.
     *
     * <p><b>It creates no task and moves no state</b>: the operation is a send, not a convergence, and
     * it writes nothing this application stores. <b>A terminated employee is refused first</b>, by
     * the same guard {@link #apply(UUID, UUID)} applies: a fresh activation link is a way back in.
     * The remaining refusal comes from the capability it delegates to — no linked account
     * ({@link com.lifecontrol.api.provisioning.exception.AccountNotLinkedException}) — and it answers
     * <b>409</b> because it extends the conflict category (record T86), so the operator sees which one
     * applies instead of a 500.</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws EmployeeNotFoundException when the employee does not belong to the company
     * @throws RevokedEmployeeRefusedException when the employee's status is {@code Terminated}
     * @throws com.lifecontrol.api.provisioning.exception.AccountNotLinkedException when nothing is
     *     linked to send to
     */
    @Transactional
    public void resend(UUID companyId, UUID employeeId) {
        currentUserContext.verifyCompanyAccess(companyId);
        var employee = requireEmployeeInCompany(companyId, employeeId);

        if (EmployeeStatuses.isTerminated(employee)) {
            throw new RevokedEmployeeRefusedException(employeeId);
        }
        accountService.resendInvitation(employee);
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
