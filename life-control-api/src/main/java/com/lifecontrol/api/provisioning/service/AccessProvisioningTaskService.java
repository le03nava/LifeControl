package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.exception.AccessProvisioningTaskAlreadyOpenException;
import com.lifecontrol.api.provisioning.exception.AccessProvisioningTaskNotFoundException;
import com.lifecontrol.api.provisioning.exception.InvalidTaskStatusTransitionException;
import com.lifecontrol.api.provisioning.model.AccessProvisioningAppliedRole;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskKind;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningAppliedRoleRepository;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
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
    private static final Map<AccessProvisioningTaskStatus, Set<AccessProvisioningTaskStatus>> TRANSITIONS = Map.of(
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
    private final AccessProvisioningAppliedRoleRepository appliedRoleRepository;
    private final EmployeeRepository employeeRepository;
    private final CurrentUserContext currentUserContext;

    public AccessProvisioningTaskService(
            AccessProvisioningTaskRepository taskRepository,
            AccessProvisioningAppliedRoleRepository appliedRoleRepository,
            EmployeeRepository employeeRepository,
            CurrentUserContext currentUserContext) {
        this.taskRepository = taskRepository;
        this.appliedRoleRepository = appliedRoleRepository;
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
     * Claims a {@code PENDING} task for a worker: {@code PENDING → RUNNING}, {@code attempts += 1}
     * and the retry deadline cleared ({@code next_attempt_at = null}, record T42).
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
        task.setNextAttemptAt(null);
        return taskRepository.save(task);
    }

    /**
     * Marks a {@code RUNNING} task converged — {@code RUNNING → APPLIED}, {@code appliedAt = now} —
     * and writes the <b>applied snapshot</b> in the same transaction: one
     * {@code access_provisioning_applied_roles} row per role the run touched.
     *
     * <p><b>Why the snapshot rides this signature instead of a second call.</b> The snapshot means
     * "what the run touched" — {@code granted ∪ removed} (record T35), never a final state — and it
     * is written <b>inside</b> the {@code APPLIED} transaction (record T60). A separate
     * {@code recordAppliedRoles} call would be a second transaction, and a process dying between the
     * two would leave {@code APPLIED} with no record of what it applied — the record's own class of
     * defect, the one {@link #markFailed(UUID, UUID, String, LocalDateTime)} was corrected for. That
     * is also why the one-argument form is <b>replaced</b> and not overloaded: two ways to reach one
     * edge is what record T17 forbids, and the replaced form had no production caller.</p>
     *
     * <p><b>The parameter type is {@code Set}, and the invariant is structural.</b> The table's
     * {@code UNIQUE (task_id, role_name)} means one row per role; a {@code Set} makes a duplicate
     * impossible before any guard could be written, whereas a {@code List} would need a
     * de-duplication step that is easy to forget. The caller passes {@code granted ∪ removed}, which
     * {@link com.lifecontrol.api.provisioning.dto.RoleConvergenceResult} already normalizes to an
     * unmodifiable {@code TreeSet}, but this method does not lean on that: it copies the names into
     * its own {@link TreeSet} so the written order is ascending <b>by construction here</b> and never
     * depends on the caller's iteration order. That matters because a {@code HashSet} from any future
     * caller would write the batch in an arbitrary order, and the read side
     * ({@code findByTaskIdOrderByCreatedAtAsc}) orders by {@code created_at}, which is identical for
     * every row of one batch — so the write order is the only order a reader could ever see.</p>
     *
     * <p><b>The clock is read once.</b> {@code appliedAt} and every row's {@code createdAt} are the
     * same value, so the task's edge and its snapshot cannot disagree about when the run landed. The
     * entity's {@code @PrePersist} only fills {@code created_at} when it is null, so the explicit
     * value wins and the shared instant is what is persisted.</p>
     *
     * <p><b>The empty snapshot is deliberate, not an error.</b> A run that touched nothing — a person
     * already converged — is a legitimate {@code APPLIED} with zero rows (record T35); the method
     * skips the write entirely rather than issuing an empty batch. The dangerous reading is the
     * opposite one: a {@code Terminated} employee requires nothing, so a "final state" snapshot would
     * write zero rows for a run that removed everything, which is exactly why the caller passes what
     * was <b>touched</b>.</p>
     *
     * <p><b>Refusals.</b> A blank or {@code null} role name is refused with
     * {@link IllegalArgumentException} before the task is even read: the column is {@code NOT NULL}
     * but PostgreSQL accepts the empty string, so a blank name would otherwise be stored silently as a
     * role the task never applied. The column's 100-character width is deliberately <b>not</b>
     * duplicated here — the database is the authority on its own type, and a second literal in Java
     * would be one more copy to drift. A longer name is passed through untruncated and the column
     * refuses it, rolling the whole transaction back; silently truncating to a valid-but-wrong role
     * name would record a grant that never happened.</p>
     *
     * @param appliedRoleNames the roles the run touched ({@code granted ∪ removed}); an empty set is
     *     valid and writes no rows
     * @return the task, moved to {@code APPLIED} with {@code appliedAt} stamped
     * @throws IllegalArgumentException when the set is {@code null} or any name is {@code null} or blank
     * @throws AccessProvisioningTaskNotFoundException when the task is unknown or belongs to another
     *     employee
     * @throws InvalidTaskStatusTransitionException when the task is not {@code RUNNING}
     */
    @Transactional
    public AccessProvisioningTask markApplied(UUID employeeId, UUID taskId, Set<String> appliedRoleNames) {
        var roleNames = requireUsableRoleNames(appliedRoleNames);
        var task = loadForUpdate(taskId, employeeId);
        guardTransition(task, AccessProvisioningTaskStatus.APPLIED);

        var appliedAt = LocalDateTime.now();
        task.setAppliedAt(appliedAt);
        var saved = taskRepository.save(task);

        if (!roleNames.isEmpty()) {
            appliedRoleRepository.saveAll(roleNames.stream()
                    .map(roleName -> AccessProvisioningAppliedRole.builder()
                            .task(saved)
                            .roleName(roleName)
                            .createdAt(appliedAt)
                            .build())
                    .toList());
        }
        return saved;
    }

    /**
     * Records a visible failure (<b>and</b> the automatic reschedule when a deadline is given):
     * {@code RUNNING → FAILED}, {@code lastError = reason} and, only when
     * {@code nextAttemptAt != null}, the guarded {@code FAILED → PENDING} in the same transaction with
     * {@code next_attempt_at} set to that deadline.
     *
     * <p>Both edges go through the same guard; neither is an unguarded status write. The reschedule
     * keeps {@code last_error}, so the reason stays visible while the task waits (record T10), and a
     * {@code null} deadline means the retry ceiling was reached (record T43): the task stays
     * {@code FAILED} and its deadline is cleared.</p>
     *
     * <p>The deadline is a {@link LocalDateTime} — the column's own type, the same one
     * {@code requestedAt} and {@code appliedAt} use — and it is a parameter, never computed here: the
     * backoff policy is W4a's {@code common.worker.WorkerRetryPolicy}, and the state machine stays a
     * guard over an explicit transition map with no scheduling policy and no clock of its own (record
     * T46). The caller composes the value from its own clock and the policy's {@code Duration}, for
     * example {@code LocalDateTime.now().plus(policy.backoffFor(attempts))}. Spanning both edges in
     * one transaction is what removes the window a separate {@code markFailed} + automatic
     * {@code retry} pair would leave — a process that died between them would strand a below-ceiling
     * task in {@code FAILED}, out of the automatic queue.</p>
     *
     * @throws AccessProvisioningTaskNotFoundException when the task is unknown or belongs to another
     *     employee
     * @throws InvalidTaskStatusTransitionException when the task is not {@code RUNNING}
     */
    @Transactional
    public AccessProvisioningTask markFailed(UUID employeeId, UUID taskId, String reason, LocalDateTime nextAttemptAt) {
        var task = loadForUpdate(taskId, employeeId);
        guardTransition(task, AccessProvisioningTaskStatus.FAILED);
        task.setLastError(reason);
        if (nextAttemptAt == null) {
            task.setNextAttemptAt(null);
        } else {
            guardTransition(task, AccessProvisioningTaskStatus.PENDING);
            task.setNextAttemptAt(nextAttemptAt);
        }
        return taskRepository.save(task);
    }

    /**
     * Returns a failed task to the queue <b>due immediately</b>: {@code FAILED → PENDING},
     * {@code lastError = null} so the next attempt starts clean, and {@code next_attempt_at = null}
     * so the operator's manual retry is never backoff-delayed (record T46). This is the
     * <b>operator's</b> path, distinct from the worker's automatic reschedule, which is
     * {@link #markFailed(UUID, UUID, String, LocalDateTime)}.
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
        task.setNextAttemptAt(null);
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

    /**
     * The applied-snapshot names as a sorted, duplicate-free set, refusing anything that could not be
     * read back as a role. The {@link TreeSet} is built here rather than trusted from the caller, so
     * the persisted order is ascending regardless of the input's iteration order; a {@code null} set
     * is refused rather than read as empty, because "touched nothing" must be an explicit
     * {@code Set.of()} and a {@code null} is a caller bug that would otherwise erase the snapshot.
     */
    private static SortedSet<String> requireUsableRoleNames(Set<String> appliedRoleNames) {
        if (appliedRoleNames == null) {
            throw new IllegalArgumentException(
                    "Refusing to record the applied snapshot: the role-name set is null; pass an empty"
                            + " set when the run touched no role");
        }
        var names = new TreeSet<String>();
        for (var roleName : appliedRoleNames) {
            if (roleName == null || roleName.isBlank()) {
                throw new IllegalArgumentException(
                        "Refusing to record a blank role name in the applied snapshot: " + roleName);
            }
            names.add(roleName);
        }
        return names;
    }
}
