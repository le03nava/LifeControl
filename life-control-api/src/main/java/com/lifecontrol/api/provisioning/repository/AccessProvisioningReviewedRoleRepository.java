package com.lifecontrol.api.provisioning.repository;

import com.lifecontrol.api.provisioning.model.AccessProvisioningReviewedRole;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the frozen reviewed set of an access-provisioning task.
 *
 * <p>The set is replaced <b>whole</b> when a task returns to the gate (decision T72): {@link
 * #deleteAllByTaskId(UUID)} removes the frozen rows and the caller writes the replacement in the same
 * transaction, so there is never a window with a partial or an absent reviewed set. Its single writer
 * is the gate service, which is why no partial delete exists.</p>
 *
 * <ul>
 *   <li>{@link #findByTaskIdOrderByRoleNameAsc} — the roles one task froze for review, ordered by the
 *       role name so the set is a stable value rather than the insertion order.</li>
 *   <li>{@link #deleteAllByTaskId} — clears the frozen set before the re-derived one is written when
 *       the task returns to the gate.</li>
 * </ul>
 */
@Repository
public interface AccessProvisioningReviewedRoleRepository extends JpaRepository<AccessProvisioningReviewedRole, UUID> {

    List<AccessProvisioningReviewedRole> findByTaskIdOrderByRoleNameAsc(UUID taskId);

    /**
     * Removes every reviewed row of one task, so the re-derived set can replace the frozen set as a
     * whole. Called by the gate service when a task returns to the gate; nothing else deletes reviewed
     * rows.
     */
    void deleteAllByTaskId(UUID taskId);
}
