package com.lifecontrol.api.hr.repository;

import com.lifecontrol.api.hr.model.Position;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the company-scoped position catalog.
 *
 * <p>The company-wide queries derive the company through the association
 * ({@code department.company.id}, written as the property path {@code DepartmentCompanyId}) because
 * {@code positions} never duplicates {@code company_id} (decision T7). The department-filtered
 * queries are safe on their own because the service resolves the department scoped to the company
 * before it runs them. Every lookup is ordered by {@code displayOrder} ascending and then
 * {@code positionCode} ascending.</p>
 */
@Repository
public interface PositionRepository extends JpaRepository<Position, UUID> {

    List<Position> findByDepartmentCompanyIdOrderByDisplayOrderAscPositionCodeAsc(UUID companyId);

    List<Position> findByDepartmentCompanyIdAndEnabledTrueOrderByDisplayOrderAscPositionCodeAsc(UUID companyId);

    List<Position> findByDepartmentIdOrderByDisplayOrderAscPositionCodeAsc(UUID departmentId);

    List<Position> findByDepartmentIdAndEnabledTrueOrderByDisplayOrderAscPositionCodeAsc(UUID departmentId);

    Optional<Position> findByDepartmentCompanyIdAndId(UUID companyId, UUID id);

    boolean existsByDepartmentIdAndPositionCode(UUID departmentId, String positionCode);

    boolean existsByDepartmentIdAndPositionCodeAndIdNot(UUID departmentId, String positionCode, UUID excludeId);

    boolean existsByDepartmentIdAndPositionName(UUID departmentId, String positionName);

    boolean existsByDepartmentIdAndPositionNameAndIdNot(UUID departmentId, String positionName, UUID excludeId);
}
