package com.lifecontrol.api.provisioning.repository;

import com.lifecontrol.api.provisioning.model.AccessProvisioningAppliedRole;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the applied snapshot of an access-provisioning task.
 *
 * <p>There is deliberately no {@code deleteBy}-style method: the snapshot is immutable history, and
 * a role the task did not apply is simply absent from it.</p>
 *
 * <ul>
 *   <li>{@link #findByTaskIdOrderByCreatedAtAsc} — the roles one task recorded, oldest first, so the
 *       allowlist test (record T12) and the Access section (W5) can read them as rows. Its only
 *       consumer inside W1a is the schema suite's round-trip.</li>
 * </ul>
 */
@Repository
public interface AccessProvisioningAppliedRoleRepository extends JpaRepository<AccessProvisioningAppliedRole, UUID> {

    List<AccessProvisioningAppliedRole> findByTaskIdOrderByCreatedAtAsc(UUID taskId);
}
