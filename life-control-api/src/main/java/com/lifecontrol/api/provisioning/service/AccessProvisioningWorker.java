package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.common.worker.WorkerRetryPolicy;
import com.lifecontrol.api.common.worker.WorkerTick;
import com.lifecontrol.api.config.provisioning.ProvisioningWorkerProperties;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * The access-provisioning worker: the ordered pass that rescues rows stranded in {@code RUNNING} and
 * then claims and applies the due ones through the existing guarded edges.
 *
 * <p><b>The defect this repairs, measured.</b> The state machine's recovery-relevant exits from
 * {@code RUNNING} are {@code APPLIED} and {@code FAILED} (the third target, {@code APPROVAL_PENDING},
 * is reached only by this worker's re-derivation check below, never by a recovery), while the
 * automatic queue selects only {@code PENDING} and the operator's {@code retry} <b>is</b> the
 * {@code FAILED → PENDING} edge and refuses {@code RUNNING}. A process that died after claiming a
 * task therefore leaves a row the queue and the operator's retry cannot move, and because
 * {@code RUNNING} sits inside
 * {@code AccessProvisioningTaskService.OPEN_STATUSES} and inside the partial unique index
 * {@code uq_access_provisioning_tasks_open}'s predicate, {@code create} refuses a second intent for
 * that employee and they can never be provisioned again until someone edits the database (record
 * T53, record G20). The repair needs <b>no new edge and no migration</b>: it calls the existing
 * {@link AccessProvisioningTaskService#markFailed}, which guards the transition under its own
 * pessimistic write lock.</p>
 *
 * <p><b>No transaction of its own (record T57).</b> This class declares no {@code @Transactional},
 * mirroring {@link WorkerTick}, which deliberately leaves the transaction boundary on the domain side
 * (record T44). Every write it causes happens inside a service method that already guards it:
 * {@code markFailed} is {@code @Transactional} and performs both the guarded {@code RUNNING → FAILED}
 * and, when a deadline is given, the guarded {@code FAILED → PENDING} in the same transaction. The
 * pass reads the query, resolves each id to its employee, and delegates — it owns no lock and no
 * guard of its own.</p>
 *
 * <p><b>The ids-to-employee bridge carries no authority (record T51).</b> The repository returns
 * <b>ids</b>; the only locked finder is the employee-scoped
 * {@code findByIdAndEmployeeIdForUpdate(id, employeeId)}, so the pass learns the employee through the
 * repository's inherited <b>unlocked</b> {@code findById}. That read only tells the pass whose task to
 * attempt to rescue: the guarded, locked re-load <b>inside</b> {@code markFailed} is what decides, so a
 * row that moved on between the query and the rescue is refused by the guard — and that refusal,
 * thrown out of the action, is contained by {@link WorkerTick} rather than allowed to abort the pass.
 * A row that no longer exists at all is skipped, not failed.</p>
 *
 * <p><b>The deadline is the normal failure's deadline (record T58).</b> The pass composes
 * {@code now.plus(policy.backoffFor(attempts))} — the exact value the ordinary failure path passes to
 * {@code markFailed} — and {@code null} when {@code policy.exhausted(attempts)}. A {@code null}
 * deadline means the ceiling was reached and the row stays {@code FAILED} for the operator; a
 * {@code null} deadline <em>unconditionally</em> would be a second, undocumented retry policy that
 * makes every crash need an operator click while the person stays blocked.</p>
 *
 * <p><b>The clock is read once per pass.</b> One {@code LocalDateTime.now()} feeds both the staleness
 * bound ({@code now - stalenessThresholdSeconds}) and every rescue deadline, so the row's age and its
 * next attempt cannot disagree about "now". The type is {@code LocalDateTime} — the columns' own
 * type — and no {@code ZoneId.systemDefault()} conversion happens here, which is the hidden
 * environment dependency this module's worker substrate exists to refuse.</p>
 *
 * <p><b>Phase-level failures propagate.</b> The {@code findStaleRunningTaskIds} call sits outside the
 * tick, so a broken query throws out of this method instead of being swallowed: the scheduled caller
 * (Spring's own {@code @Scheduled} error handler) logs it and keeps the schedule, whereas a catch here
 * would hide a dead query behind a green tick. Only the <b>per-row</b> failure is contained, and that
 * containment belongs to {@link WorkerTick}.</p>
 *
 * <p><b>The claim loop is the pass's other half, and the rescue leads it.</b> {@link #runPass()} runs
 * the rescue before it queries the due rows, so a stranded row is freed before the batch is spent on
 * new work; both phases share the same {@link WorkerTick}, so the batch is bounded and one row's
 * failure cannot abort the pass. The due query is read with a single {@code LocalDateTime.now()} that
 * also composes every failure deadline, keeping "is this due?" and "when is the next attempt?" on
 * one clock.</p>
 *
 * <p><b>A failure the recovery can see does not leave a claimed row {@code RUNNING}.</b> From the
 * moment {@link AccessProvisioningTaskService#claim} succeeds the row has no path back except the
 * ones the state machine guards, so every failure between the claim and the outcome that the
 * recovery block observes — a handler refusal, an exception out of a capability, a missing employee
 * — ends in {@link AccessProvisioningTaskService#markFailed} with a visible reason and the policy's
 * deadline, composed from the <b>claimed</b> row's incremented attempts. The reason is bounded to
 * {@link #LAST_ERROR_MAX_LENGTH} characters on purpose: a reason longer than
 * {@code last_error VARCHAR(500)} would make the recovery write itself fail and strand the row
 * {@code RUNNING}, which is the exact hole this class exists to close, so a truncated message is
 * strictly better than a failed transition here.</p>
 *
 * <p><b>Two escapes are left to the rescue on purpose, and widening the catch is refused.</b> The
 * recovery catches {@link RuntimeException} only — {@link WorkerTick}'s own containment does the
 * same — so an {@link Error} thrown after the claim escapes the pass; and the recovery block itself
 * can throw before {@code markFailed} runs, either out of {@code deadlineFor} (its
 * {@code Duration.multipliedBy} overflows into an {@link ArithmeticException} on a pathological
 * {@code max-delay-seconds}/attempts binding) or out of {@code markFailed} when the row has moved
 * on. In both cases the claimed row stays {@code RUNNING} until the staleness rescue collects it, a
 * window bounded by {@code interval-seconds} plus {@code staleness-threshold-seconds}. Both are
 * deliberate: catching {@link Throwable} would swallow genuine JVM failures, and the rescue — which
 * leads this same pass for exactly this reason — already bounds the consequence.</p>
 *
 * <p><b>A claim the guard refuses is not this pass's row to fail.</b> The refusal is thrown by
 * {@code claim} <b>before</b> the recovery block, so it propagates into {@link WorkerTick}'s
 * containment and the next due row still runs; failing a row this pass never claimed would overwrite a
 * status another worker set.</p>
 */
@Service
public class AccessProvisioningWorker {

    /** The width of {@code access_provisioning_tasks.last_error}, the column every reason lands in. */
    static final int LAST_ERROR_MAX_LENGTH = 500;

    private final AccessProvisioningTaskRepository taskRepository;
    private final AccessProvisioningTaskService taskService;
    private final EmployeeRepository employeeRepository;
    private final AccessProvisioningWorkerHandler handler;
    private final AccessProvisioningGateService gateService;
    private final WorkerTick tick;
    private final WorkerRetryPolicy retryPolicy;
    private final ProvisioningWorkerProperties properties;

    public AccessProvisioningWorker(
            AccessProvisioningTaskRepository taskRepository,
            AccessProvisioningTaskService taskService,
            EmployeeRepository employeeRepository,
            AccessProvisioningWorkerHandler handler,
            AccessProvisioningGateService gateService,
            WorkerTick tick,
            WorkerRetryPolicy retryPolicy,
            ProvisioningWorkerProperties properties) {
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.employeeRepository = employeeRepository;
        this.handler = handler;
        this.gateService = gateService;
        this.tick = tick;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
    }

    /**
     * Runs one full pass, in this order: the staleness rescue first, then the due claims.
     *
     * <p><b>The rescue leads.</b> {@link #rescueStaleRunningTasks()} runs before the due query, so a
     * row stranded in {@code RUNNING} is freed before this pass spends its batch on new work; it is
     * the same pass, not a second scheduler, because the stranded row blocks that employee's
     * provisioning and must not wait another interval.</p>
     *
     * <p><b>One clock read for the due phase.</b> A single {@code LocalDateTime.now()} feeds both the
     * due query and every failure deadline this pass composes, so "is this row due?" and "when is the
     * next attempt?" cannot disagree about now. The type is the columns' own and no
     * {@code ZoneId.systemDefault()} conversion happens here, matching the rescue's discipline.</p>
     *
     * <p><b>The phase failure propagates.</b> The {@code findDueTaskIds} call sits outside the tick,
     * exactly like the rescue's finder: a broken query throws out of this method and the scheduled
     * caller (Spring's {@code @Scheduled} error handler) logs it and keeps the schedule, whereas a
     * catch here would hide a dead query behind a green report. Only the per-row failure is contained,
     * and that containment belongs to {@link WorkerTick}.</p>
     *
     * @return the claim phase's {@link WorkerTick.TickReport}. The rescue's own report is deliberately
     *     not merged into it: a {@code TickReport} describes one tick, and the rescue is a tick of its
     *     own whose durable outcome is each row's status, not a counter
     */
    public WorkerTick.TickReport runPass() {
        rescueStaleRunningTasks();
        var now = LocalDateTime.now();
        var dueIds = taskRepository.findDueTaskIds(now, PageRequest.of(0, properties.batchSize()));
        return tick.run(dueIds, taskId -> process(taskId, now));
    }

    /**
     * Runs one rescue pass and reports what the tick attempted and what it had to contain.
     *
     * <p>The staleness bound is the pass's single clock read minus
     * {@code app.provisioning.worker.staleness-threshold-seconds}; the candidates are then bounded by
     * {@code app.provisioning.worker.batch-size} at the query and again by {@link WorkerTick}.</p>
     *
     * @return the tick's {@link WorkerTick.TickReport}: {@code attempted} counts every stale id handed
     *     to the rescue action (a vanished row is attempted but neither rescued nor failed) and
     *     {@code failed} counts the rescues whose {@code markFailed} threw
     */
    public WorkerTick.TickReport rescueStaleRunningTasks() {
        var now = LocalDateTime.now();
        var staleBefore = now.minusSeconds(properties.stalenessThresholdSeconds());
        var staleIds = taskRepository.findStaleRunningTaskIds(staleBefore, PageRequest.of(0, properties.batchSize()));
        var reason = stalenessReason();
        return tick.run(staleIds, taskId -> rescue(taskId, now, reason));
    }

    /**
     * Claims and dispatches one due row, and ends every failure it can see in
     * {@code markFailed} rather than leaving the row {@code RUNNING}.
     *
     * <p><b>The unlocked read only routes.</b> {@code findById} tells this method which employee owns
     * the task and nothing more; a row that vanished between the query and this read is skipped
     * without a claim, because there is nothing left to claim.</p>
     *
     * <p><b>The claim is the authority and sits outside the recovery.</b>
     * {@link AccessProvisioningTaskService#claim} re-loads the row under its pessimistic write lock and
     * returns the saved task, so its {@code kind} is what the dispatch uses and its incremented
     * {@code attempts} is what the deadline is composed from — the unlocked snapshot above carries no
     * authority (record T51). A claim the guard refuses (the row moved on, or another worker won it)
     * throws out of here and is contained by {@link WorkerTick}: the row was never ours, so this method
     * must not fail it.</p>
     *
     * <p><b>The re-gate check runs before the apply.</b> Once the employee is loaded and still
     * <b>before</b> {@link AccessProvisioningWorkerHandler#apply} runs, the task's frozen reviewed
     * set is re-derived by
     * {@link AccessProvisioningGateService#reenterGateIfReviewedSetChanged(com.lifecontrol.api.hr.model.Employee, java.util.UUID)}
     * and, when the truth moved since the approval, the task is returned to the gate and this method
     * returns immediately: nothing is applied against a stale snapshot. That early return is not a
     * failure — it neither calls {@code markApplied} nor {@code markFailed} — because the gate service
     * already moved the task and replaced the frozen rows in its own transaction.</p>
     *
     * <p><b>Every other failure the recovery can see is recorded.</b> From the moment the claim
     * succeeds the row is {@code RUNNING}, and the only status with a legal exit is the one
     * {@code markFailed} performs. A handler refusal, an exception out of a capability, or a missing
     * employee therefore ends in {@code markFailed} with the exception's reason and the policy's
     * deadline — never in a silent return that would strand the row and block the employee's
     * provisioning (records T10/T53). What the {@code catch} cannot see — an {@link Error}, or a throw
     * out of the recovery block itself — is left to the staleness rescue on purpose, as the class
     * javadoc records.</p>
     */
    private void process(UUID taskId, LocalDateTime now) {
        var resolved = taskRepository.findById(taskId).orElse(null);
        if (resolved == null) {
            return;
        }
        var employeeId = resolved.getEmployee().getId();
        var claimed = taskService.claim(employeeId, taskId);
        try {
            var employee = employeeRepository.findById(employeeId).orElse(null);
            if (employee == null) {
                throw new EmployeeNotFoundException(employeeId);
            }
            if (gateService.reenterGateIfReviewedSetChanged(employee, taskId)) {
                // The task is back at the gate: nothing may be applied and this is not a failure.
                return;
            }
            var touched = handler.apply(employee, claimed.getKind());
            taskService.markApplied(employeeId, taskId, touched);
        } catch (RuntimeException failure) {
            var attempts = claimed.getAttempts() == null ? 0 : claimed.getAttempts();
            taskService.markFailed(employeeId, taskId, failureReason(failure), deadlineFor(attempts, now));
        }
    }

    /**
     * The failure deadline every automatic failure gets: {@code now + policy.backoffFor(attempts)}, and
     * {@code null} at the ceiling so the row stays {@code FAILED} for the operator (records T43/T58).
     * Both the rescue and the claim loop compose their deadline here so the two paths cannot drift.
     */
    private LocalDateTime deadlineFor(int attempts, LocalDateTime now) {
        return retryPolicy.exhausted(attempts) ? null : now.plus(retryPolicy.backoffFor(attempts));
    }

    /**
     * The reason written to {@code last_error}: the exception's message when it has one, its class name
     * otherwise, bounded to {@link #LAST_ERROR_MAX_LENGTH} characters.
     *
     * <p><b>Why this truncates where the applied snapshot refuses to.</b>
     * {@link AccessProvisioningTaskService#markApplied} deliberately lets an over-long role name reach
     * the database and roll the whole transaction back, because the column is the authority on its own
     * width and silently storing a valid-but-wrong role name would record a grant that never happened.
     * Here the failed write is <b>worse</b> than a truncated message: a reason longer than
     * {@code VARCHAR(500)} would make {@code markFailed} itself fail, and a failed recovery write
     * leaves the claimed row {@code RUNNING} — the exact hole this class exists to close. The first 500
     * characters of a message are worth more to the operator than no transition at all, so the reason
     * is cut to fit rather than passed through.</p>
     */
    private static String failureReason(RuntimeException failure) {
        var message = failure.getMessage();
        var reason = (message == null || message.isBlank()) ? failure.getClass().getName() : message;
        return reason.length() <= LAST_ERROR_MAX_LENGTH ? reason : reason.substring(0, LAST_ERROR_MAX_LENGTH);
    }

    /**
     * Rescues one stranded row: resolves its employee through the unlocked read, then hands the guard
     * the failure and the deadline it should get. A vanished row is skipped; a guard refusal throws
     * out of here and is contained by {@link WorkerTick}.
     */
    private void rescue(UUID taskId, LocalDateTime now, String reason) {
        var task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        var employeeId = task.getEmployee().getId();
        var attempts = task.getAttempts() == null ? 0 : task.getAttempts();
        taskService.markFailed(employeeId, taskId, reason, deadlineFor(attempts, now));
    }

    private String stalenessReason() {
        return "Staleness rescue: the task stayed RUNNING without an update for more than "
                + properties.stalenessThresholdSeconds()
                + " seconds, so the worker failed it through the existing RUNNING -> FAILED edge.";
    }
}
