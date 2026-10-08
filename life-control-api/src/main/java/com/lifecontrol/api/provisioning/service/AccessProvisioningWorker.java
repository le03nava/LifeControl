package com.lifecontrol.api.provisioning.service;

import com.lifecontrol.api.common.worker.WorkerRetryPolicy;
import com.lifecontrol.api.common.worker.WorkerTick;
import com.lifecontrol.api.config.provisioning.ProvisioningWorkerProperties;
import com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * The access-provisioning worker's <b>rescue pass</b>: it reaps {@code RUNNING} rows whose process
 * died between the claim and its outcome, through the existing {@code RUNNING → FAILED} edge.
 *
 * <p><b>The defect this repairs, measured.</b> The state machine maps {@code RUNNING} to exactly
 * {@code {APPLIED, FAILED}} and {@code FAILED} to {@code {PENDING}}, while the automatic queue selects
 * only {@code PENDING} and the operator's {@code retry} <b>is</b> the {@code FAILED → PENDING} edge
 * and refuses {@code RUNNING}. A process that died after claiming a task therefore leaves a row with
 * <b>no legal exit at all</b>, and because {@code RUNNING} sits inside
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
 */
@Service
public class AccessProvisioningWorker {

    private final AccessProvisioningTaskRepository taskRepository;
    private final AccessProvisioningTaskService taskService;
    private final WorkerTick tick;
    private final WorkerRetryPolicy retryPolicy;
    private final ProvisioningWorkerProperties properties;

    public AccessProvisioningWorker(
            AccessProvisioningTaskRepository taskRepository,
            AccessProvisioningTaskService taskService,
            WorkerTick tick,
            WorkerRetryPolicy retryPolicy,
            ProvisioningWorkerProperties properties) {
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.tick = tick;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
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
        var deadline = retryPolicy.exhausted(attempts) ? null : now.plus(retryPolicy.backoffFor(attempts));
        taskService.markFailed(employeeId, taskId, reason, deadline);
    }

    private String stalenessReason() {
        return "Staleness rescue: the task stayed RUNNING without an update for more than "
                + properties.stalenessThresholdSeconds()
                + " seconds, so the worker failed it through the existing RUNNING -> FAILED edge.";
    }
}
