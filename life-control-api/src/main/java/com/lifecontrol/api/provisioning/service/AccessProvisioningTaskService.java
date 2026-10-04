package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.exception.AccessProvisioningTaskAlreadyOpenException;
import com.lifecontrol.api.provisioning.exception.AccessProvisioningTaskNotFoundException;
import com.lifecontrol.api.provisioning.exception.InvalidTaskStatusTransitionException;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The state machine of {@code access_provisioning_tasks} and the service that owns its guard.
 *
 * <p>There is exactly one explicit transition map ({@link #TRANSITIONS}) holding the six legal
 * edges: {@code PENDING → RUNNING}, {@code RUNNING → APPLIED}, {@code RUNNING → FAILED},
 * {@code FAILED → PENDING}, {@code APPROVAL_PENDING → PENDING} and {@code APPROVAL_PENDING →
 * REJECTED}. {@code APPLIED} and {@code REJECTED} are terminal, self-transitions are illegal, and
 * no other edge exists. Every one of them is applied here and every illegal pair is refused with
 * {@link InvalidTaskStatusTransitionException}.</p>
 *
 * <p>{@link #guardTransition(AccessProvisioningTask, AccessProvisioningTaskStatus)} is the
 * <b>only</b> code path in this class, and the only one this unit exposes to W4/W5, that assigns
 * {@link AccessProvisioningTask#setStatus(AccessProvisioningTaskStatus)}, so a caller of this class
 * cannot move a status without passing the map. That claim is scoped to this class, not to the
 * repository as a whole: W1a's entity exposes a public
 * {@link AccessProvisioningTask#setStatus(AccessProvisioningTaskStatus)} and its builder assigns the
 * field directly — an affordance recorded as advisory finding R3-1 in W1a — and no caller in this
 * record may use it. Creation selects the <b>initial</b> state and therefore goes
 * through the same guard: only {@code PENDING} (the derived diff is inside the auto-apply set) and
 * {@code APPROVAL_PENDING} (the gate applies) are accepted, and any other value is refused before
 * the task exists (record T18).</p>
 *
 * <p>The table carries <b>no {@code version} column and the entity has no {@code @Version}</b>
 * (record T2), so a pessimistic write lock is the only serialization this design has. <b>Every</b>
 * status-moving operation ({@code claim}, {@code markApplied}, {@code markFailed}, {@code retry},
 * {@code approve}, {@code reject}) loads its task through
 * {@code findByIdAndEmployeeIdForUpdate}, not only the claim: two callers that read the same
 * {@code APPROVAL_PENDING} row and both pass the guard would otherwise let the second {@code save}
 * silently overwrite the first decision, and the row <b>is</b> the audit (record T2/T9), so a lost
 * decision is a lost record. With the lock, the loser of a race re-reads the committed status after
 * the lock is released and is refused with {@link InvalidTaskStatusTransitionException} rather than
 * overwriting it. <b>W4 owns proving this atomicity against the database</b> (its scheduler and its
 * concurrency test); <b>this class owns making the bypass impossible</b> — a conditional
 * {@code UPDATE … WHERE status = ...} would be a second, unguarded door into a status, and the
 * guarded methods are the only paths this unit exposes (record T17).</p>
 *
 * <p>Every operation loads its task through the <b>employee-scoped pessimistic-write</b> finder
 * {@link AccessProvisioningTaskRepository#findByIdAndEmployeeIdForUpdate}, so a task cannot be
 * reached through another employee and a miss is an
 * {@link AccessProvisioningTaskNotFoundException} (404). The plain
 * {@link AccessProvisioningTaskRepository#findByIdAndEmployeeId} is deliberately not used here: it
 * serves W5's read paths. Creation is company-scoped first:
 * {@link CurrentUserContext#verifyCompanyAccess(UUID)} runs before the employee is resolved, the
 * {@code ContractService.resolveCompany} convention.</p>
 */
@Service
public class AccessProvisioningTaskService {

    /**
     * The six legal edges of the machine, and nothing else. Terminal statuses are present with an
     * empty set so "no outgoing edge" is stated rather than implied by absence.
     */
    private static final Map<AccessProvisioningTaskStatus, Set<AccessProvisioningTaskStatus>> TRANSITIONS =
            Map.of(
                    AccessProvisioningTaskStatus.PENDING, Set.of(AccessProvisioningTaskStatus.RUNNING),
                    AccessProvisioningTaskStatus.RUNNING,
                            Set.of(AccessProvisioningTaskStatus.APPLIED, AccessProvisioningTaskStatus.FAILED),
                    AccessProvisioningTaskStatus.FAILED, Set.of(AccessProvisioningTaskStatus.PENDING),
                    AccessProvisioningTaskStatus.APPROVAL_PENDING,
                            Set.of(AccessProvisioningTaskStatus.PENDING, AccessProvisioningTaskStatus.REJECTED),
                    AccessProvisioningTaskStatus.APPLIED, Set.of(),
                    AccessProvisioningTaskStatus.REJECTED, Set.of());

    /**
     * The statuses a task may be <b>created</b> in: {@code PENDING} when the derived diff is inside
     * the auto-apply set, {@code APPROVAL_PENDING} when the gate applies. This is creation, not an
     * edge, which is why it is not part of {@link #TRANSITIONS}.
     */
    private static final Set<AccessProvisioningTaskStatus> INITIAL_STATUSES =
            Set.of(AccessProvisioningTaskStatus.PENDING, AccessProvisioningTaskStatus.APPROVAL_PENDING);

    /**
     * The open set the partial unique index {@code uq_access_provisioning_tasks_open} declares,
     * verbatim: a second open task for the same employee is refused. The index remains the real race
     * guard; this set is the pre-check.
     */
    private static final Set<AccessProvisioningTaskStatus> OPEN_STATUSES = Set.of(
            AccessProvisioningTaskStatus.PENDING,
            AccessProvisioningTaskStatus.APPROVAL_PENDING,
            AccessProvisioningTaskStatus.RUNNING,
            AccessProvisioningTaskStatus.FAILED);

    private final AccessProvisioningTaskRepository taskRepository;
    private final EmployeeRepository employeeRepository;
    private final CurrentUserContext currentUserContext;

    public AccessProvisioningTaskService(
            AccessProvisioningTaskRepository taskRepository,
            EmployeeRepository employeeRepository,
            CurrentUserContext currentUserContext) {
        this.taskRepository = taskRepository;
        this.employeeRepository = employeeRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Writes the durable intent for an employee, in the status creation selects.
     *
     * <p>The company access check is the first statement, then the employee is resolved through the
     * company-scoped {@code findByIdAndCompanyId}, so a foreign or unknown employee is an
     * {@link EmployeeNotFoundException} (404) rather than a created task. A second open task for the
     * same employee is refused with {@link AccessProvisioningTaskAlreadyOpenException} using
     * {@link #OPEN_STATUSES}; the partial unique index stays the real race guard.</p>
     *
     * <p>{@code initialStatus} is handed to the guard, which accepts only {@link #INITIAL_STATUSES}
     * and refuses anything else with {@link InvalidTaskStatusTransitionException} before the row is
     * written.</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws EmployeeNotFoundException when the employee does not belong to the company
     * @throws InvalidTaskStatusTransitionException when {@code initialStatus} is not {@code PENDING}
     *     or {@code APPROVAL_PENDING}
     * @throws AccessProvisioningTaskAlreadyOpenException when the employee already has an open task
     */
    @Transactional
    public AccessProvisioningTask create(
            UUID companyId,
            UUID employeeId,
            AccessProvisioningTaskKind kind,
            String requestedBy,
            AccessProvisioningTaskStatus initialStatus) {
        currentUserContext.verifyCompanyAccess(companyId);
        var employee = employeeRepository
                .findByIdAndCompanyId(employeeId, companyId)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeId));

        if (taskRepository.existsByEmployeeIdAndStatusIn(employeeId, OPEN_STATUSES)) {
            throw new AccessProvisioningTaskAlreadyOpenException(employeeId);
        }

        var task = AccessProvisioningTask.builder()
                .employee(employee)
                .kind(kind)
                .requestedBy(requestedBy)
                .requestedAt(LocalDateTime.now())
                .attempts(0)
                .build();
        guardTransition(task, initialStatus);

        return taskRepository.save(task);
    }

    /**
     * Claims a {@code PENDING} task for a worker: {@code PENDING → RUNNING} and {@code attempts += 1}.
     *
     * <p>The row is read under a pessimistic write lock
     * ({@link AccessProvisioningTaskRepository#findByIdAndEmployeeIdForUpdate}) before the transition
     * is validated, so two workers cannot both claim the same task. The attempt exists from the
     * moment the row is claimed, which is why the increment happens here and not at apply time.</p>
     *
     * @throws AccessProvisioningTaskNotFoundException when the task is unknown or belongs to another
     *     employee
     * @throws InvalidTaskStatusTransitionException when the task is not {@code PENDING}
     */
    @Transactional
    public AccessProvisioningTask claim(UUID employeeId, UUID taskId) {
        var task = loadForUpdate(taskId, employeeId);
        guardTransition(task, AccessProvisioningTaskStatus.RUNNING);
        task.setAttempts((task.getAttempts() == null ? 0 : task.getAttempts()) + 1);
        return taskRepository.save(task);
    }

    /**
     * Marks a {@code RUNNING} task converged: {@code RUNNING → APPLIED}, {@code appliedAt = now}.
     *
     * @throws AccessProvisioningTaskNotFoundException when the task is unknown or belongs to another
     *     employee
     * @throws InvalidTaskStatusTransitionException when the task is not {@code RUNNING}
     */
    @Transactional
    public AccessProvisioningTask markApplied(UUID employeeId, UUID taskId) {
        var task = loadForUpdate(taskId, employeeId);
        guardTransition(task, AccessProvisioningTaskStatus.APPLIED);
        task.setAppliedAt(LocalDateTime.now());
        return taskRepository.save(task);
    }

    /**
     * Records a visible failure: {@code RUNNING → FAILED} and {@code lastError = reason}.
     *
     * @throws AccessProvisioningTaskNotFoundException when the task is unknown or belongs to another
     *     employee
     * @throws InvalidTaskStatusTransitionException when the task is not {@code RUNNING}
     */
    @Transactional
    public AccessProvisioningTask markFailed(UUID employeeId, UUID taskId, String reason) {
        var task = loadForUpdate(taskId, employeeId);
        guardTransition(task, AccessProvisioningTaskStatus.FAILED);
        task.setLastError(reason);
        return taskRepository.save(task);
    }

    /**
     * Returns a failed task to the queue: {@code FAILED → PENDING} and {@code lastError = null}, so
     * the next attempt starts clean.
     *
     * @throws AccessProvisioningTaskNotFoundException when the task is unknown or belongs to another
     *     employee
     * @throws InvalidTaskStatusTransitionException when the task is not {@code FAILED}
     */
    @Transactional
    public AccessProvisioningTask retry(UUID employeeId, UUID taskId) {
        var task = loadForUpdate(taskId, employeeId);
        guardTransition(task, AccessProvisioningTaskStatus.PENDING);
        task.setLastError(null);
        return taskRepository.save(task);
    }

    /**
     * Releases a gated task to the worker: {@code APPROVAL_PENDING → PENDING}, with {@code decidedBy}
     * and {@code decidedAt} recorded. Approval never applies anything (record T18); the diff is
     * re-derived when the worker applies it (record T11).
     *
     * @throws AccessProvisioningTaskNotFoundException when the task is unknown or belongs to another
     *     employee
     * @throws InvalidTaskStatusTransitionException when the task is not {@code APPROVAL_PENDING}
     */
    @Transactional
    public AccessProvisioningTask approve(UUID employeeId, UUID taskId, String decidedBy) {
        var task = loadForUpdate(taskId, employeeId);
        guardTransition(task, AccessProvisioningTaskStatus.PENDING);
        task.setDecidedBy(decidedBy);
        task.setDecidedAt(LocalDateTime.now());
        return taskRepository.save(task);
    }

    /**
     * Refuses a gated task: {@code APPROVAL_PENDING → REJECTED}, with {@code decidedBy} and
     * {@code decidedAt} recorded, and the {@code reason} persisted in {@code last_error} — the
     * table's only free-text column, which is left visible on the terminal row (record T19).
     *
     * @throws AccessProvisioningTaskNotFoundException when the task is unknown or belongs to another
     *     employee
     * @throws InvalidTaskStatusTransitionException when the task is not {@code APPROVAL_PENDING}
     */
    @Transactional
    public AccessProvisioningTask reject(UUID employeeId, UUID taskId, String decidedBy, String reason) {
        var task = loadForUpdate(taskId, employeeId);
        guardTransition(task, AccessProvisioningTaskStatus.REJECTED);
        task.setDecidedBy(decidedBy);
        task.setDecidedAt(LocalDateTime.now());
        task.setLastError(reason);
        return taskRepository.save(task);
    }

    /**
     * The state machine's <b>only guard</b> and the only code path in this class that assigns
     * {@link AccessProvisioningTask#setStatus(AccessProvisioningTaskStatus)}. It delegates the
     * decision to {@link #requireTransition} and, only when the pair is legal, writes the target
     * status. A caller of this class therefore cannot move a state without passing the map.
     */
    private void guardTransition(AccessProvisioningTask task, AccessProvisioningTaskStatus target) {
        requireTransition(task.getStatus(), target);
        task.setStatus(target);
    }

    /**
     * The pure decision behind the guard: creation ({@code from == null}) accepts only
     * {@link #INITIAL_STATUSES}; any other pair must be an edge of {@link #TRANSITIONS}, otherwise it
     * is refused with {@link InvalidTaskStatusTransitionException}.
     *
     * <p>A {@code null} target is refused up front, in both the creation branch and the edge branch,
     * with {@link InvalidTaskStatusTransitionException}: it must not reach {@code Set.of(...).contains}
     * and surface as a {@link NullPointerException}.</p>
     *
     * <p>Package-private and side-effect free so the exhaustive matrix test can assert every one of
     * the 6 × 6 status pairs without mutating an entity. It never assigns a status, which is what
     * keeps {@link #guardTransition} the only writer.</p>
     */
    static void requireTransition(AccessProvisioningTaskStatus from, AccessProvisioningTaskStatus target) {
        if (target == null) {
            throw new InvalidTaskStatusTransitionException(from == null ? "creation" : from.name(), "null");
        }
        if (from == null) {
            if (!INITIAL_STATUSES.contains(target)) {
                throw new InvalidTaskStatusTransitionException("creation", target.name());
            }
            return;
        }
        var allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(target)) {
            throw new InvalidTaskStatusTransitionException(from.name(), target.name());
        }
    }

    private AccessProvisioningTask loadForUpdate(UUID taskId, UUID employeeId) {
        return taskRepository
                .findByIdAndEmployeeIdForUpdate(taskId, employeeId)
                .orElseThrow(() -> new AccessProvisioningTaskNotFoundException(taskId));
    }
}
