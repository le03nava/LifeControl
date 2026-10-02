package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.dto.DepartmentRequest;
import com.lifecontrol.api.hr.dto.DepartmentResponse;
import com.lifecontrol.api.hr.exception.DepartmentNotFoundException;
import com.lifecontrol.api.hr.exception.DuplicateDepartmentException;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.repository.DepartmentRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the company-scoped department catalog.
 *
 * <p>This is the repository's first company-scoped catalog. Every public method starts by resolving
 * the company through {@link #resolveCompany(UUID)}, whose first statement is
 * {@link CurrentUserContext#verifyCompanyAccess(UUID)} — the company-scope contract (record E12).
 * The company is then loaded, so a missing company is a {@link CompanyNotFoundException}.</p>
 *
 * <p>Every single-row read and write goes through a {@code companyId}-scoped repository lookup
 * ({@code findByCompanyIdAndId}), which makes a department of another company a
 * {@link DepartmentNotFoundException} (404) rather than a 200. Both uniqueness rules — code and name
 * — are checked per company and, on update, exclude the row being updated. The check-then-write race
 * is left to {@code GlobalExceptionHandler}'s {@code DataIntegrityViolationException} mapping to 409;
 * no lock is added. {@code delete} is a soft delete: it flips {@code enabled} to {@code false}.</p>
 *
 * <p>The reads are deliberately <b>not</b> cached. A company-scoped read must run its scope check on
 * <b>every</b> call, and a {@code @Cacheable} on these methods would serve a cache hit without ever
 * reaching {@link CurrentUserContext#verifyCompanyAccess(UUID)}: a caller who knows a company id
 * could read that company's departments after any legitimate read warmed the entry. The neighbouring
 * {@code CompanyRegionService} is the pattern that <em>does</em> cache its company-scoped reads, so
 * the absence of caching here is a decision, not an omission. The global {@code seniorityLevels}
 * catalog keeps its cache because its reads contain no scope check.</p>
 */
@Service
public class DepartmentService {

    private static final Logger logger = LoggerFactory.getLogger(DepartmentService.class);

    private final DepartmentRepository departmentRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserContext currentUserContext;

    public DepartmentService(
            DepartmentRepository departmentRepository,
            CompanyRepository companyRepository,
            CurrentUserContext currentUserContext) {
        this.departmentRepository = departmentRepository;
        this.companyRepository = companyRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Verifies company access and then loads the company, in that order.
     *
     * <p>The access check is deliberately the first statement: a caller without access to the company
     * is denied before any row is loaded. This mirrors
     * {@code StoreLocationService.resolveStore}, which the record names in E12.</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws CompanyNotFoundException                                when the company does not exist
     */
    private Company resolveCompany(UUID companyId) {
        currentUserContext.verifyCompanyAccess(companyId);
        return companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));
    }

    @Transactional(readOnly = true)
    public List<DepartmentResponse> getAllDepartments(UUID companyId, boolean includeDisabled) {
        resolveCompany(companyId);

        if (includeDisabled) {
            return departmentRepository.findByCompanyIdOrderByDisplayOrderAscDepartmentCodeAsc(companyId).stream()
                    .map(this::toResponse)
                    .toList();
        }
        return departmentRepository
                .findByCompanyIdAndEnabledTrueOrderByDisplayOrderAscDepartmentCodeAsc(companyId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DepartmentResponse getDepartmentById(UUID companyId, UUID id) {
        resolveCompany(companyId);
        return departmentRepository
                .findByCompanyIdAndId(companyId, id)
                .map(this::toResponse)
                .orElseThrow(() -> new DepartmentNotFoundException(id));
    }

    @Transactional
    public DepartmentResponse createDepartment(UUID companyId, DepartmentRequest request) {
        var company = resolveCompany(companyId);

        if (departmentRepository.existsByCompanyIdAndDepartmentCode(companyId, request.departmentCode())) {
            throw new DuplicateDepartmentException("code", request.departmentCode());
        }
        if (departmentRepository.existsByCompanyIdAndDepartmentName(companyId, request.departmentName())) {
            throw new DuplicateDepartmentException("name", request.departmentName());
        }

        var department = Department.builder()
                .company(company)
                .departmentCode(request.departmentCode())
                .departmentName(request.departmentName())
                .description(request.description())
                .displayOrder(request.displayOrder())
                .enabled(request.enabled())
                .build();

        var saved = departmentRepository.save(department);
        logger.info(
                "Department created: id={}, companyId={}, code={}",
                saved.getId(),
                companyId,
                saved.getDepartmentCode());

        return toResponse(saved);
    }

    @Transactional
    public DepartmentResponse updateDepartment(UUID companyId, UUID id, DepartmentRequest request) {
        resolveCompany(companyId);

        var department = departmentRepository
                .findByCompanyIdAndId(companyId, id)
                .orElseThrow(() -> new DepartmentNotFoundException(id));

        // Both uniqueness rules are per company and exclude the row being updated.
        if (departmentRepository.existsByCompanyIdAndDepartmentCodeAndIdNot(companyId, request.departmentCode(), id)) {
            throw new DuplicateDepartmentException("code", request.departmentCode());
        }
        if (departmentRepository.existsByCompanyIdAndDepartmentNameAndIdNot(companyId, request.departmentName(), id)) {
            throw new DuplicateDepartmentException("name", request.departmentName());
        }

        department.setDepartmentCode(request.departmentCode());
        department.setDepartmentName(request.departmentName());
        department.setDescription(request.description());
        department.setDisplayOrder(request.displayOrder());
        department.setEnabled(request.enabled());

        var updated = departmentRepository.save(department);
        logger.info(
                "Department updated: id={}, companyId={}, code={}",
                updated.getId(),
                companyId,
                updated.getDepartmentCode());

        return toResponse(updated);
    }

    @Transactional
    public void deleteDepartment(UUID companyId, UUID id) {
        resolveCompany(companyId);

        var department = departmentRepository
                .findByCompanyIdAndId(companyId, id)
                .orElseThrow(() -> new DepartmentNotFoundException(id));

        department.setEnabled(false);
        departmentRepository.save(department);

        logger.info(
                "Department soft-deleted: id={}, companyId={}, code={}", id, companyId, department.getDepartmentCode());
    }

    @Transactional
    public DepartmentResponse setDepartmentEnabled(UUID companyId, UUID id, boolean enabled) {
        resolveCompany(companyId);

        var department = departmentRepository
                .findByCompanyIdAndId(companyId, id)
                .orElseThrow(() -> new DepartmentNotFoundException(id));

        department.setEnabled(enabled);
        var saved = departmentRepository.save(department);

        logger.info("Department enabled={}: id={}, companyId={}", enabled, id, companyId);

        return toResponse(saved);
    }

    private DepartmentResponse toResponse(Department department) {
        return new DepartmentResponse(
                department.getId(),
                department.getCompany().getId(),
                department.getDepartmentCode(),
                department.getDepartmentName(),
                department.getDescription(),
                department.getDisplayOrder(),
                department.getEnabled(),
                department.getCreatedAt(),
                department.getUpdatedAt());
    }
}
