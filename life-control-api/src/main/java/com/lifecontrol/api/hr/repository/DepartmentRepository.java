package com.lifecontrol.api.hr.repository;

import com.lifecontrol.api.hr.model.Department;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    List<Department> findByCompanyIdOrderByDisplayOrderAscDepartmentCodeAsc(UUID companyId);

    List<Department> findByCompanyIdAndEnabledTrueOrderByDisplayOrderAscDepartmentCodeAsc(UUID companyId);

    Optional<Department> findByCompanyIdAndId(UUID companyId, UUID id);

    boolean existsByCompanyIdAndDepartmentCode(UUID companyId, String departmentCode);

    boolean existsByCompanyIdAndDepartmentCodeAndIdNot(UUID companyId, String departmentCode, UUID excludeId);

    boolean existsByCompanyIdAndDepartmentName(UUID companyId, String departmentName);

    boolean existsByCompanyIdAndDepartmentNameAndIdNot(UUID companyId, String departmentName, UUID excludeId);
}
