package com.lifecontrol.api.provisioning.repository;

import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 * </ul>
 */
@Repository
public interface AccessProvisioningTaskRepository extends JpaRepository<AccessProvisioningTask, UUID> {

    Optional<AccessProvisioningTask> findByIdAndEmployeeId(UUID id, UUID employeeId);

    List<AccessProvisioningTask> findByEmployeeIdOrderByRequestedAtDesc(UUID employeeId);

    boolean existsByEmployeeIdAndStatusIn(UUID employeeId, Collection<AccessProvisioningTaskStatus> statuses);

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
}
