package com.lifecontrol.api.provisioning.repository;

import com.lifecontrol.api.provisioning.model.AccessProvisioningReviewedRole;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the frozen reviewed set of an access-provisioning task.
 *
 * <p>There is deliberately no {@code deleteBy}-style method: the set is replaced <b>whole</b> when a
 * task returns to the gate (decision T72), so its writer reads the current set, decides and writes
 * the replacement in one transaction — a partial delete would be a second way to reach the same
 * state.</p>
 *
 * <ul>
 *   <li>{@link #findByTaskIdOrderByRoleNameAsc} — the roles one task froze for review, ordered by the
 *       role name so the set is a stable value rather than the insertion order. Its only consumer
 *       inside this unit is the schema suite's round-trip; the gate and the re-gate check arrive in
 *       W5b's later rounds.</li>
 * </ul>
 */
@Repository
public interface AccessProvisioningReviewedRoleRepository extends JpaRepository<AccessProvisioningReviewedRole, UUID> {

    List<AccessProvisioningReviewedRole> findByTaskIdOrderByRoleNameAsc(UUID taskId);
}
