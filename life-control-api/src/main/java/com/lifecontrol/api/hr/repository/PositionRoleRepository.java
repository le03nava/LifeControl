package com.lifecontrol.api.hr.repository;

import com.lifecontrol.api.hr.model.PositionRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the role template of a position.
 *
 * <p>Both finders are company-agnostic: the service resolves the position through a company-scoped
 * query first, so a foreign position is already a 404 before either method runs.</p>
 *
 * <p>The read returns <b>every</b> stored row, disabled ones included. That is the declared cost of
 * the omission rule — the client must be able to tell "explicitly cleared" from "never configured" —
 * so there is deliberately no {@code enabled}-filtered variant and no {@code includeDisabled}
 * flag.</p>
 *
 * <p>There is deliberately <b>no</b> {@code deleteByPositionId}-style method: an omitted role is
 * disabled, never deleted (record T20).</p>
 */
@Repository
public interface PositionRoleRepository extends JpaRepository<PositionRole, UUID> {

    List<PositionRole> findByPositionIdOrderByRoleNameAsc(UUID positionId);

    Optional<PositionRole> findByPositionIdAndRoleName(UUID positionId, String roleName);
}
