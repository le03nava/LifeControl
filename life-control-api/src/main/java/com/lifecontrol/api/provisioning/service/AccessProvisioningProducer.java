package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.service.EmployeeStatuses;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The <b>automatic producer</b> of the employee-access projection (unit W5c, slice S3): it turns an
 * organisational fact — a contract that starts covering today, an employment that ends — into the
 * durable intent the worker reconciles. It is what closes <b>G21</b>, the gap that kept the worker's
 * queue empty by construction because nothing in {@code src/main} ever called
 * {@link AccessProvisioningTaskService#create}.
 *
 * <p><b>It owns no transaction, on purpose.</b> The intent has to be written in the
 * <b>same transaction that asserts the fact</b> — the schema's own words — so this class joins
 * whatever transaction its caller opened ({@code ContractService.createContract} and
 * {@code EmployeeService.updateEmployee} are both {@code @Transactional}) and the row commits or
 * rolls back with the fact. Adding a boundary here would change nothing about that and would only add
 * a place where the propagation could be misread.</p>
 *
 * <p><b>It asks before it enqueues, instead of catching the refusal.</b> The partial unique index
 * {@code uq_access_provisioning_tasks_open} allows at most one open task per employee, so a second
 * intent is refused with {@link com.lifecontrol.api.provisioning.exception.AccessProvisioningTaskAlreadyOpenException}
 * and the fact must still commit — an open task of the same kind already represents it, while one of
 * a <b>different</b> kind does not and is superseded first
 * ({@link AccessProvisioningTaskService#supersede}). The obvious shape would be to call the gate and
 * catch that refusal, and it is <b>wrong</b>: a {@link RuntimeException} that
 * crosses a participating {@code @Transactional} boundary marks the surrounding transaction
 * rollback-only (Spring's documented behaviour for propagations that join an existing transaction), so
 * the catch would leave the fact to die at commit with an {@code UnexpectedRollbackException} — a
 * failure mode strictly worse than the refusal it meant to swallow. Asking first keeps the common path
 * exception-free; the pre-check uses the landed
 * {@link AccessProvisioningTaskService#OPEN_STATUSES}, so "open" still has <b>one</b> definition and
 * the race remains the index's job. But the pre-check alone is not enough: two facts for the same
 * employee asserted at the same instant, from transactions that do not write the employee row (a
 * contract insert, a store-assignment write), would both pass it and the second would be refused at
 * commit, rolling the fact back. The decision is therefore <b>serialized on the employee row</b>:
 * {@link #enqueue} takes a pessimistic write lock on that row
 * ({@code EmployeeRepository#findByIdAndCompanyIdForUpdate}) <b>before</b> the first read of the open
 * set, inside the caller's transaction, so the second transaction waits for the first to commit and
 * then sees its open task instead of racing it. The index stays the guarantee; the lock is what
 * stops a legitimate fact from being the one refused. The order is always employee &rarr; task for
 * a given writer, and only that order: taking the lock after the read would still lose the race,
 * and taking it after a task lock would deadlock against the facts this class serves.</p>
 *
 * <p><b>The kind is derived from the fact and the employee's own state</b>, and this is the reason the
 * class exists instead of the two callers naming a kind: a contract that starts covering a person with
 * <b>no</b> account needs {@code ACTIVATE} (create or link the account and send the invitation), while
 * the same fact for a person who is <b>already</b> linked needs {@code RECONCILE} — {@code ACTIVATE}
 * would re-send an invitation nobody asked for, and {@code RECONCILE} refuses on an unlinked account
 * ({@code AccountNotLinkedException}), so a wrong choice is not a cosmetic difference. The termination
 * is always {@code DEACTIVATE} (rule O4).</p>
 *
 * <p><b>Only the already-open case is tolerated</b> — suppressed for the same kind, superseded for a
 * different one. Every other refusal and failure propagates and
 * the fact rolls back with it — including the live identity-provider read the gate performs to derive
 * the diff, which is the coupling declared as <b>G40</b>. Swallowing those would write an intent
 * against a diff nobody derived, which is the un-reviewed apply T67 exists to forbid.</p>
 *
 * <p><b>The actor is the JWT {@code sub} claim</b> ({@link CurrentUserContext#getUserId()}, record
 * T83): the operator whose HTTP request asserted the fact is the one who asked for the provision,
 * which is exactly what the gate records as the task's {@code requested_by} — the same basis rule O3
 * compares against the deciding actor.</p>
 */
@Service
public class AccessProvisioningProducer {

    private static final Logger logger = LoggerFactory.getLogger(AccessProvisioningProducer.class);

    private final CurrentUserContext currentUserContext;
    private final AccessProvisioningGateService gateService;
    private final AccessProvisioningTaskRepository taskRepository;
    private final AccessProvisioningTaskService taskService;
    private final EmployeeRepository employeeRepository;

    public AccessProvisioningProducer(
            CurrentUserContext currentUserContext,
            AccessProvisioningGateService gateService,
            AccessProvisioningTaskRepository taskRepository,
            AccessProvisioningTaskService taskService,
            EmployeeRepository employeeRepository) {
        this.currentUserContext = currentUserContext;
        this.gateService = gateService;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.employeeRepository = employeeRepository;
    }

    /**
     * Produces the intent a contract start implies: {@code ACTIVATE} for an employee with no linked
     * account, {@code RECONCILE} for one that is already linked.
     *
     * <p>The caller invokes this <b>after</b> the contract row is saved and inside the same
     * transaction, so a contract that never landed never enqueues anything.</p>
     *
     * <p><b>A revoked employee produces nothing</b> (gap G41, record
     * {@code employee-disable-revokes-access}): a {@code Terminated} one cannot open a contract at all
     * ({@code ContractService} refuses it), and a <b>soft-deleted</b> one can — the very ordering G41
     * was reachable through. Enqueueing here would supersede the open {@code DEACTIVATE} that still has
     * to apply, and an unlinked revoked employee would even get an {@code ACTIVATE}, i.e. an account and
     * an invitation nobody asked for. The revocation owns the fact until the flag or the status changes.</p>
     *
     * @param companyId the company that owns the employee
     * @param employee the employee the new contract belongs to; only its linked-account flag and its
     *     revoked state are read
     */
    public void onContractActivated(UUID companyId, Employee employee) {
        if (EmployeeStatuses.isRevoked(employee)) {
            logNotEnqueuedForRevoked(employee, "a contract start");
            return;
        }
        enqueue(companyId, employee, kindFor(employee));
    }

    /**
     * Produces the revocation intent an employment end implies: always {@code DEACTIVATE} (rule O4 —
     * disable the account, remove the template's roles, remove the claim attributes).
     *
     * <p>The caller invokes this only on the <b>transition</b> into a terminated status, never on every
     * write of an already-terminated employee: a revocation that already applied is a terminal task,
     * and re-enqueueing it on every later edit would open a new intent for a fact that did not
     * change.</p>
     *
     * @param companyId the company that owns the employee
     * @param employee the employee whose employment ended
     */
    public void onEmploymentTerminated(UUID companyId, Employee employee) {
        enqueue(companyId, employee, AccessProvisioningTaskKind.DEACTIVATE);
    }

    /**
     * Produces the revocation intent a <b>soft delete</b> implies: always {@code DEACTIVATE}, exactly the
     * intent an employment end produces, because gap <b>G41</b> made the two facts one verdict — an
     * employee out of the registry holds no access (record {@code employee-disable-revokes-access}).
     *
     * <p>The caller invokes this only on the <b>transition</b> into {@code enabled = false}, so editing an
     * already disabled employee opens no new intent.</p>
     *
     * <p><b>An unlinked employee produces nothing.</b> Unlike an employment end — which is a fact of the
     * employment and is recorded even with no account, because the status change is what it says — a
     * soft delete revokes <b>access</b>, and an employee who never got an account has none to revoke:
     * {@code AccessProvisioningAccountService.deactivate} refuses an unlinked employee by contract, so
     * enqueueing would be a task that can only ever end {@code FAILED}.</p>
     *
     * @param companyId the company that owns the employee
     * @param employee the employee who was soft-deleted
     */
    public void onSoftDelete(UUID companyId, Employee employee) {
        var linked = employee.getKeycloakUserId();
        if (linked == null || linked.isBlank()) {
            logger.info(
                    "Access provisioning intent not enqueued: employee={} was soft-deleted but has no linked"
                            + " account, so there is no access to revoke",
                    employee.getId());
            return;
        }
        enqueue(companyId, employee, AccessProvisioningTaskKind.DEACTIVATE);
    }

    /**
     * Produces the reconciliation intent a <b>re-enable</b> implies: always {@code RECONCILE}, so the
     * roles and the claims the soft delete removed come back from the contract that covers today — or
     * stay empty when no contract covers it.
     *
     * <p><b>No kind is derived here, deliberately</b> (gap G41): {@link #kindFor(Employee)} answers
     * {@code ACTIVATE} for an unlinked employee, and a re-enable is not the moment to create an account
     * and send an invitation nobody asked for — the same rule {@link #onMembershipChanged(UUID, Employee)}
     * follows. An unlinked employee therefore produces nothing: there is no account to restore, and a
     * later contract start is the fact that activates one.</p>
     *
     * <p><b>A revoked employee produces nothing</b> either: after the flag was set back to {@code true}
     * only the {@code Terminated} status can still make it revoked, and a terminated employee's roles
     * belong to the open revocation, not to a convergence.</p>
     *
     * @param companyId the company that owns the employee
     * @param employee the employee whose access was restored
     */
    public void onAccessRestored(UUID companyId, Employee employee) {
        if (EmployeeStatuses.isRevoked(employee)) {
            logNotEnqueuedForRevoked(employee, "a re-enable");
            return;
        }
        var linked = employee.getKeycloakUserId();
        if (linked == null || linked.isBlank()) {
            logger.info(
                    "Access provisioning intent not enqueued: employee={} has no linked account to restore",
                    employee.getId());
            return;
        }
        enqueue(companyId, employee, AccessProvisioningTaskKind.RECONCILE);
    }

    /**
     * Produces the reconciliation intent a <b>membership change</b> implies: always {@code RECONCILE},
     * and only for an employee the projection can actually converge.
     *
     * <p>It is deliberately <b>source-neutral</b>. Every writer that moves the derived scope calls
     * this one hook — {@code EmployeeScopeAssignmentService} when a country, region, zone or store
     * assignment opens or closes — so neither the prose here nor the refusal log may name a source: a
     * message that says "store" for a scope write is a log that lies about the fact it refused. The
     * source is not lost, because the caller logs the fact with more detail one line earlier (its own
     * INFO entry names the assignment id, the node and the dates).</p>
     *
     * <p>This is the trigger <b>G38</b> was missing: the writers of
     * {@code EmployeeScopeAssignmentService} save their row and return, so a change of assignment
     * moves the derivation {@code ScopeAssignmentDerivation} computes — the five {@code company_*}
     * claims {@code CurrentUserContext} reads for scope — and nothing enqueued a convergence, leaving
     * the claims at the old scope until another fact enqueued a task. {@code employee-scope-assignments-per-level}
     * widened the same gap to the per-level assignments and reused this hook rather than adding a
     * second one, which is why the fact it serves is now "the membership changed" and not "a store
     * assignment changed".</p>
     *
     * <p><b>No kind is derived here.</b> {@link #kindFor(Employee)} answers {@code ACTIVATE} for an
     * unlinked employee, which is exactly wrong for this fact: a membership change is never the
     * moment to create an account and send an invitation nobody asked for, and {@code RECONCILE}
     * refuses outright on an unlinked account. The kind is a constant because the fact is one.</p>
     *
     * <p><b>Two employees produce nothing, for two different reasons</b> (decision <b>S2</b>): an
     * <b>unlinked</b> employee has no account to converge, and a <b>revoked</b> one — {@code Terminated}
     * or soft-deleted (gap G41) — must not supersede the open {@code DEACTIVATE} that still has to
     * apply — {@link #enqueue} supersedes an open task of a different kind, and nothing in the
     * {@code RECONCILE} convergence disables an account
     * ({@code AccessProvisioningAccountService.ensureAccountEnabled} no-ops for a revoked employee), so a
     * naive {@code RECONCILE} would leave the revoked account enabled. Both guards return before
     * {@link #enqueue}, so neither the open revocation nor the open-task read is touched.</p>
     *
     * @param companyId the company that owns the employee
     * @param employee the employee whose membership changed
     */
    public void onMembershipChanged(UUID companyId, Employee employee) {
        if (EmployeeStatuses.isRevoked(employee)) {
            logNotEnqueuedForRevoked(employee, "a membership change");
            return;
        }

        var linked = employee.getKeycloakUserId();
        if (linked == null || linked.isBlank()) {
            logger.info(
                    "Access provisioning intent not enqueued: employee={} has no linked account to converge",
                    employee.getId());
            return;
        }

        enqueue(companyId, employee, AccessProvisioningTaskKind.RECONCILE);
    }

    private void logNotEnqueuedForRevoked(Employee employee, String fact) {
        logger.info(
                "Access provisioning intent not enqueued: employee={} is revoked (terminated or soft-deleted), so"
                        + " the open revocation must not be superseded by {}",
                employee.getId(),
                fact);
    }

    private void enqueue(UUID companyId, Employee employee, AccessProvisioningTaskKind kind) {
        // The lock precedes the read: two transactions that both reach the pre-check would otherwise
        // both pass it and one would be refused by uq_access_provisioning_tasks_open at commit,
        // losing the fact that produced it. Holding the employee row serializes the whole decision —
        // read, suppress-or-supersede, create — for the rest of this transaction.
        employeeRepository.findByIdAndCompanyIdForUpdate(employee.getId(), companyId);

        var open = taskRepository.findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
                employee.getId(), AccessProvisioningTaskService.OPEN_STATUSES);
        if (open.isPresent() && open.get().getKind() == kind) {
            logger.info(
                    "Access provisioning intent not enqueued: employee={} already has an open task of kind={}, which"
                            + " already represents this fact",
                    employee.getId(),
                    kind);
            return;
        }

        open.ifPresent(task -> taskService.supersede(
                employee.getId(),
                task.getId(),
                currentUserContext.getUserId(),
                "Superseded by a newer fact: the open " + task.getKind()
                        + " task no longer represents the employee's truth, and a " + kind + " intent does"));

        gateService.request(companyId, employee.getId(), kind, currentUserContext.getUserId());
    }

    /**
     * The kind a contract start implies, read from the employee's own linkage: an unlinked person has
     * no account to converge, so the intent has to be {@code ACTIVATE}; a linked one already has it.
     */
    private AccessProvisioningTaskKind kindFor(Employee employee) {
        var linked = employee.getKeycloakUserId();
        return linked == null || linked.isBlank()
                ? AccessProvisioningTaskKind.ACTIVATE
                : AccessProvisioningTaskKind.RECONCILE;
    }
}
