package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.hr.model.Employee;
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
 * the race remains the index's job. A genuine race — two facts for the same employee at the same
 * instant — propagates the landed conflict and rolls the fact back, which is declared rather than
 * hidden (record G39).</p>
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

    public AccessProvisioningProducer(
            CurrentUserContext currentUserContext,
            AccessProvisioningGateService gateService,
            AccessProvisioningTaskRepository taskRepository,
            AccessProvisioningTaskService taskService) {
        this.currentUserContext = currentUserContext;
        this.gateService = gateService;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
    }

    /**
     * Produces the intent a contract start implies: {@code ACTIVATE} for an employee with no linked
     * account, {@code RECONCILE} for one that is already linked.
     *
     * <p>The caller invokes this <b>after</b> the contract row is saved and inside the same
     * transaction, so a contract that never landed never enqueues anything.</p>
     *
     * @param companyId the company that owns the employee
     * @param employee the employee the new contract belongs to; only its linked-account flag is read
     */
    public void onContractActivated(UUID companyId, Employee employee) {
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
     * Produces the reconciliation intent a <b>store-assignment change</b> implies: always
     * {@code RECONCILE}, and only for an employee the projection can actually converge.
     *
     * <p>This is the trigger <b>G38</b> was missing: the two writers of
     * {@code EmployeeStoreAssignmentService} save their row and return, so a change of assignment
     * moves the derivation {@code StoreScopeDerivation} computes — the five {@code company_*} claims
     * {@code CurrentUserContext} reads for scope — and nothing enqueued a convergence, leaving the
     * claims at the old scope until another fact enqueued a task.</p>
     *
     * <p><b>No kind is derived here.</b> {@link #kindFor(Employee)} answers {@code ACTIVATE} for an
     * unlinked employee, which is exactly wrong for this fact: an assignment change is never the
     * moment to create an account and send an invitation nobody asked for, and {@code RECONCILE}
     * refuses outright on an unlinked account. The kind is a constant because the fact is one.</p>
     *
     * <p><b>Two employees produce nothing, for two different reasons</b> (decision <b>S2</b>): an
     * <b>unlinked</b> employee has no account to converge, and a <b>{@code Terminated}</b> one must
     * not supersede the open {@code DEACTIVATE} that still has to apply — {@link #enqueue}
     * supersedes an open task of a different kind, and nothing in the {@code RECONCILE} convergence
     * disables an account ({@code AccessProvisioningAccountService.ensureAccountEnabled} no-ops for a
     * terminated employee), so a naive {@code RECONCILE} would leave the revoked account enabled.
     * Both guards return before {@link #enqueue}, so neither the open revocation nor the open-task
     * read is touched.</p>
     *
     * @param companyId the company that owns the employee
     * @param employee the employee whose store assignment changed
     */
    public void onMembershipChanged(UUID companyId, Employee employee) {
        if (EmployeeStatuses.isTerminated(employee)) {
            logger.info(
                    "Access provisioning intent not enqueued: employee={} is terminated, so the open revocation"
                            + " must not be superseded",
                    employee.getId());
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

    private void enqueue(UUID companyId, Employee employee, AccessProvisioningTaskKind kind) {
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
