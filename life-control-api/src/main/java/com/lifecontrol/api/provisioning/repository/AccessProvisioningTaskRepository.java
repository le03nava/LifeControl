package com.lifecontrol.api.provisioning.repository;

import com.lifecontrol.api.provisioning.model.AccessProvisioningTask;
import com.lifecontrol.api.provisioning.model.AccessProvisioningTaskStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
 *       any check runs (W5's approve/reject/retry endpoints). Its only consumer inside W1a is the
 *       schema suite's round-trip.</li>
 *   <li>{@link #findByEmployeeIdOrderByRequestedAtDesc} — the employee's access history, newest
 *       first, for the Access section (W5). Its only consumer inside W1a is the schema suite's
 *       round-trip.</li>
 *   <li>{@link #existsByEmployeeIdAndStatusIn} — the "is there an open task" probe, used by the
 *       creation path to refuse a second intent and by W5's screen to show the open one. The database
 *       still owns the guarantee through {@code uq_access_provisioning_tasks_open}; this is the
 *       pre-check, not the lock. Its only consumer inside W1a is the schema suite's round-trip.</li>
 * </ul>
 */
@Repository
public interface AccessProvisioningTaskRepository extends JpaRepository<AccessProvisioningTask, UUID> {

    Optional<AccessProvisioningTask> findByIdAndEmployeeId(UUID id, UUID employeeId);

    List<AccessProvisioningTask> findByEmployeeIdOrderByRequestedAtDesc(UUID employeeId);

    boolean existsByEmployeeIdAndStatusIn(UUID employeeId, Collection<AccessProvisioningTaskStatus> statuses);
}
