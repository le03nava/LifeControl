package com.lifecontrol.api.provisioning.repository;

import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the durable access-provisioning intent.
 *
 * <p>There is deliberately no {@code deleteBy}-style method: nothing is deleted here. A task is a
 * durable audit row, and its life ends in a terminal status ({@code APPLIED} or {@code REJECTED}),
 * never in a {@code DELETE}.</p>
 *
 * <p>The three derived methods are the whole surface the flow needs; each one is listed with its
 * consumer so an unused method is visible rather than assumed:</p>
 *
 * <ul>
 *   <li>{@link #findByIdAndEmployeeId} — resolves a task <b>scoped to its employee</b>, mirroring
 *       {@code EmployeeRepository#findByIdAndCompanyId}: a task of another employee is a 404 before
 *       any check runs (W5's read paths). Its only consumer inside W1a is the schema suite's
 *       round-trip; it is deliberately not used by the state-moving paths, which all lock.</li>
 *   <li>{@link #findByEmployeeIdOrderByRequestedAtDesc} — the employee's access history, newest
 *       first, for the Access section (W5). Its only consumer inside W1a is the schema suite's
 *       round-trip.</li>
 *   <li>{@link #existsByEmployeeIdAndStatusIn} — the "is there an open task" probe, used by the
 *       creation path to refuse a second intent and by W5's screen to show the open one. The database
 *       still owns the guarantee through {@code uq_access_provisioning_tasks_open}; this is the
 *       pre-check, not the lock. Its only consumer inside W1a is the schema suite's round-trip.</li>
 *   <li>{@link #findByIdAndEmployeeIdForUpdate} — the employee-scoped <b>pessimistic write lock</b>
 *       every status-moving operation takes before it validates the transition ({@code claim},
 *       {@code markApplied}, {@code markFailed}, {@code retry}, {@code approve}, {@code reject}), so
 *       two callers cannot read the same row and both move it. It is the serialization point of the
 *       state machine; W4 owns proving its atomicity against the database, and this repository owns
 *       making the guarded methods the only doors into a status.</li>
 *   <li>{@link #findStaleRunningTaskIds} — the rescue pass's candidates: {@code RUNNING} rows whose
 *       {@code updated_at} is older than the staleness bound, as ids so the rescue resolves each
 *       employee through the unlocked read and the guarded, locked re-load remains the authority
 *       (record T51, record T53).</li>
 *   <li>{@link #countTasksByStatus} — the queue's depth per status, read as <b>one</b> grouped
 *       aggregate for the gauges {@code AccessProvisioningQueueMetrics} publishes (S11). It is a
 *       read-only projection and carries no authority: nothing decides anything from a count.</li>
 *   <li>{@link #findOldestRequestedAtByStatus} — the age of the oldest row still in a status, so a
 *       queue that has stopped draining reads as "stuck for an hour" instead of "has items". Same
 *       consumer and same absence of authority as the count above.</li>
 * </ul>
 */
@Repository
public interface AccessProvisioningTaskRepository
        extends JpaRepository<AccessProvisioningTask, UUID>, AccessProvisioningTaskTenantResolution {

    Optional<AccessProvisioningTask> findByIdAndEmployeeId(UUID id, UUID employeeId);

    List<AccessProvisioningTask> findByEmployeeIdOrderByRequestedAtDesc(UUID employeeId);

    boolean existsByEmployeeIdAndStatusIn(UUID employeeId, Collection<AccessProvisioningTaskStatus> statuses);

    /**
     * The employee's newest <b>open</b> task, for the operator's retry (unit W5c, slice S2, record
     * T87). The retry route carries no {@code taskId}, so the row is resolved from the employee
     * against the open set the creation path already owns
     * ({@code AccessProvisioningTaskService.OPEN_STATUSES}) — one definition of "open" rather than a
     * second literal that would drift from the partial index's predicate.
     *
     * <p>{@code findFirst...OrderByRequestedAtDesc} is a tie-breaker and not a rule: the partial
     * unique index {@code uq_access_provisioning_tasks_open} allows at most one open task per
     * employee. Whether the retry is <b>legal</b> is still the state machine's answer, taken under
     * its own pessimistic lock, so this finder never decides that {@code FAILED → PENDING} applies.</p>
     */
    Optional<AccessProvisioningTask> findFirstByEmployeeIdAndStatusInOrderByRequestedAtDesc(
            UUID employeeId, Collection<AccessProvisioningTaskStatus> statuses);

    /**
     * Pessimistic write lock on the task, scoped to its employee, mirroring
     * {@code PurchaseOrderRepository#findByIdForUpdate}. Every status-moving operation takes this
     * lock before it reads the status and validates the transition ({@code claim},
     * {@code markApplied}, {@code markFailed}, {@code retry}, {@code approve}, {@code reject}), so a
     * second caller queues on the same row and re-reads the committed status instead of racing a
     * stale one.
     *
     * <p>The lock is the serialization point, not the guard: even with the row locked, only
     * {@code AccessProvisioningTaskService}'s transition map decides whether the move is legal. W4
     * owns proving this atomicity against the database; this finder is the mechanism it will
     * be proven through.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM AccessProvisioningTask t WHERE t.id = :id AND t.employee.id = :employeeId")
    Optional<AccessProvisioningTask> findByIdAndEmployeeIdForUpdate(
            @Param("id") UUID id, @Param("employeeId") UUID employeeId);

    /**
     * The due candidates of the worker's queue, as <b>task ids</b> and never as entities.
     *
     * <p>The projection is the id on purpose: the claim re-loads each row under its pessimistic
     * write lock ({@link #findByIdAndEmployeeIdForUpdate(UUID, UUID)}), so returning entities here
     * would let a caller act on a snapshot read before the lock (record T17/T18).</p>
     *
     * <p>A row is a candidate when it is {@code PENDING} — the only status the worker claims — and
     * it is <b>due</b>: either it never failed ({@code next_attempt_at IS NULL}) or it failed and its
     * backoff has elapsed ({@code next_attempt_at <= now}). {@code now} is a parameter rather than
     * {@code CURRENT_TIMESTAMP} so the caller's clock is the one the deadline was written with, and
     * the queue is FIFO through {@code ORDER BY requestedAt ASC}. The {@link Pageable} is what bounds
     * a batch; the query is deliberately <b>global</b> — it crosses companies — because the worker is
     * a platform process with no current user (record T47) and the employee's company is read inside
     * the transaction.</p>
     */
    @Query("""
            SELECT t.id FROM AccessProvisioningTask t
            WHERE t.status = com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.PENDING
              AND (t.nextAttemptAt IS NULL OR t.nextAttemptAt <= :now)
            ORDER BY t.requestedAt ASC
            """)
    List<UUID> findDueTaskIds(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * The stranded candidates of the worker's rescue pass, as <b>task ids</b> and never as entities.
     *
     * <p>The projection is the id on purpose, mirroring {@link #findDueTaskIds}: the rescue re-loads
     * each row through the inherited unlocked {@code findById} only to learn which employee owns it,
     * and that unlocked read <b>carries no authority</b> — the guarded, locked re-load inside
     * {@code AccessProvisioningTaskService#markFailed} is what decides, so a row that moved on between
     * this read and the rescue is refused rather than overwritten (record T51). Returning entities
     * here would hand the caller a snapshot taken before any lock, which is exactly what the ids-only
     * projection refuses.</p>
     *
     * <p>A row is stranded when it is still {@code RUNNING} — the status a claim writes, which only a
     * completed run or this rescue leaves — and its {@code updated_at} is older than
     * {@code staleBefore} (record T53). The clock is
     * {@link com.lifecontrol.api.common.model.Auditable#getUpdatedAt()}, and this is the <b>one</b>
     * place record T42's refusal of {@code updated_at} does not apply: T42 refused it for a
     * <em>deadline</em>, because any write to the row would move a due time, while for a
     * <em>staleness bound</em> a moving {@code updated_at} errs in the <b>safe</b> direction — a row
     * whose run is still writing keeps looking alive, so a live run is not stolen merely because the
     * clock advanced. {@code staleBefore} is a parameter rather than {@code CURRENT_TIMESTAMP} so the
     * pass's single clock read — the same value the rescue deadline is composed from — is the one the
     * bound was derived from, matching {@link #findDueTaskIds}.</p>
     *
     * <p>The order is oldest first through {@code ORDER BY updatedAt ASC}: staleness is the selection
     * axis, so the row that has been stranded longest is rescued first. The {@link Pageable} bounds the
     * batch — the same bound {@code WorkerTick} then re-applies — and the query is deliberately
     * <b>global</b> (it crosses companies) because the worker is a platform process with no current
     * user (record T47); the employee's company arrives inside the rescue's own transaction.</p>
     */
    @Query("""
            SELECT t.id FROM AccessProvisioningTask t
            WHERE t.status = com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus.RUNNING
              AND t.updatedAt < :staleBefore
            ORDER BY t.updatedAt ASC
            """)
    List<UUID> findStaleRunningTaskIds(@Param("staleBefore") LocalDateTime staleBefore, Pageable pageable);

    /*
     * `findEmployeeCompanyIdByTaskId` — the company that owns a task, resolved for the thread that has no
     * request — is declared by {@link AccessProvisioningTaskTenantResolution}, which this repository
     * extends. It is not a `@Query` here because the statement depends on the database: the
     * `SECURITY DEFINER` function `task_employee_company(uuid)` on PostgreSQL (one fixed join, one uuid
     * back, owned by the maintenance superuser because ownership is what grants the bypass), and the
     * employee join only where no ADR-0005 machinery exists and therefore no policy needs satisfying.
     * The worker keeps calling one method on this repository (record L16); which statement runs behind it
     * is the database's answer, not the caller's choice.
     */

    /**
     * The company's <b>decision queue</b>: the tasks of that company's employees waiting for a human,
     * as entities because the row a person reads carries the employee it belongs to (unit W5d, slice B,
     * record T94).
     *
     * <p>The scope enters through the employee, and it has to: {@code access_provisioning_tasks} carries
     * no {@code company_id} (V22) — {@code employee_id} is its only organisational link — so a
     * company-scoped read is a join and not a filter. The {@code JOIN FETCH} is what makes the row
     * renderable in one read: the queue entry shows the employee's number and name, and a lazy
     * {@code employee} per row would be the N+1 the fetch removes. The join is inner and correct here
     * because {@code employee_id} is {@code NOT NULL} with a foreign key, so no task can be orphaned
     * out of its company's queue.</p>
     *
     * <p>The status is a <b>parameter</b> rather than a literal: the surface that consumes this read
     * decides which set is the decision queue ({@code APPROVAL_PENDING}), and a second caller with a
     * different set does not need a second query to drift from this one. The order is the queue's own —
     * oldest request first, tie-broken by id so the order is deterministic — which is the opposite of
     * {@link #findByEmployeeIdOrderByRequestedAtDesc}: a history is read newest-first, while a queue is
     * <b>served</b> oldest-first.</p>
     *
     * <p>Entities, unlike {@link #findDueTaskIds}' and {@link #findStaleRunningTaskIds}' ids-only
     * projections, and the difference is the hazard each one avoids: those two deliberately refuse to
     * hand out a snapshot taken before a lock because their caller is about to <b>write</b> under one,
     * while this read is a pure, transactional read of rows a human is only being shown. Nothing here
     * hands authority to the caller — the decision itself goes through
     * {@code AccessProvisioningGateService}, which re-loads and scopes what it decides.</p>
     *
     * @param companyId the company whose employees' tasks are listed
     * @param status the status that defines the queue the caller is asking for
     * @return the matching rows, oldest request first
     */
    @Query("""
            SELECT t FROM AccessProvisioningTask t
            JOIN FETCH t.employee e
            WHERE e.company.id = :companyId
              AND t.status = :status
            ORDER BY t.requestedAt ASC, t.id ASC
            """)
    List<AccessProvisioningTask> findByEmployeeCompanyIdAndStatus(
            @Param("companyId") UUID companyId, @Param("status") AccessProvisioningTaskStatus status);

    /**
     * The queue's depth by status: one grouped aggregate, not one count per status.
     *
     * <p><b>One round trip on purpose.</b> The caller publishes a number per status on a timer, so a
     * per-status count would be seven queries per refresh against a table whose only interesting
     * property here is its size. {@code GROUP BY status} answers the same question once, and
     * {@code idx_access_provisioning_tasks_status} (V22) is the index it reads.</p>
     *
     * <p><b>Global, like the worker's own queue.</b> The access-provisioning worker is a platform
     * process with no current user (record T47), so the queue it drains crosses companies; a
     * company-scoped depth would describe a different, smaller queue than the one that can stall.
     * This read therefore has no tenancy scope, exactly like
     * {@link #findDueTaskIds(LocalDateTime, Pageable)} and
     * {@link #findStaleRunningTaskIds(LocalDateTime, Pageable)}.</p>
     *
     * <p>A status with no rows does not appear in the result: {@code GROUP BY} groups existing rows
     * only, and the consumer is the side that knows the full set of states.</p>
     *
     * @return one row per status the table currently holds, in no guaranteed order
     */
    @Query("""
            SELECT new com.lifecontrol.api.provisioning.repository.AccessProvisioningTaskStatusCount(
                t.status, COUNT(t))
            FROM AccessProvisioningTask t
            GROUP BY t.status
            """)
    List<AccessProvisioningTaskStatusCount> countTasksByStatus();

    /**
     * The {@code requested_at} of the oldest row in the given status, or {@code null} when no row
     * carries it.
     *
     * <p><b>Why {@code requested_at} and not {@code next_attempt_at}.</b> {@code requested_at} is when
     * the intent entered the queue, which is the fact an operator reads as "this has been waiting".
     * {@code next_attempt_at} is a deadline the worker writes <b>itself</b> on a retry, so measuring
     * from it would report the backoff as if it were the wait — and a healthy retry would then look
     * like a stalled queue for as long as {@code app.provisioning.worker.max-delay-seconds}. The cost
     * of the choice is stated where the gauge is defined: an alert threshold has to sit above that
     * ceiling, or a legitimate retry reads as a stall.</p>
     *
     * <p><b>{@code null} is a real answer, not an error.</b> {@code MIN} over an empty set is one row
     * holding {@code NULL}, so an empty queue is distinguishable from a queue whose oldest row was
     * requested at the epoch — the consumer reports {@code 0} for the former.</p>
     *
     * <p>The status is a parameter rather than a {@code PENDING} literal: this repository owns no
     * policy about which status is the claimable queue, and the caller that publishes the age is the
     * one that names it. The read is global for the same reason as
     * {@link #countTasksByStatus()}.</p>
     *
     * @param status the status whose oldest row is asked for
     * @return the oldest {@code requested_at} in that status, or {@code null} when the status is empty
     */
    @Query("""
            SELECT MIN(t.requestedAt) FROM AccessProvisioningTask t
            WHERE t.status = :status
            """)
    LocalDateTime findOldestRequestedAtByStatus(@Param("status") AccessProvisioningTaskStatus status);
}
