package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.dto.PositionRequest;
import com.lifecontrol.api.hr.dto.PositionResponse;
import com.lifecontrol.api.hr.exception.DepartmentNotFoundException;
import com.lifecontrol.api.hr.exception.DuplicatePositionException;
import com.lifecontrol.api.hr.exception.PositionNotFoundException;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.repository.DepartmentRepository;
import com.lifecontrol.api.hr.repository.PositionRepository;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the company-scoped position catalog.
 *
 * <p>Every public method starts by resolving the company through {@link #resolveCompany(UUID)},
 * whose first statement is {@link CurrentUserContext#verifyCompanyAccess(UUID)} — the company-scope
 * contract (record E12). The company is then loaded, so a missing company is a
 * {@link CompanyNotFoundException}. Positions inherit their company through the department
 * (decision T7), so a department that is not in the company of the path is a
 * {@link DepartmentNotFoundException}, and every single-row read goes through a
 * {@code companyId}-scoped query that makes another company's position a
 * {@link PositionNotFoundException} (404).</p>
 *
 * <p>The per-department uniqueness rules — code and name — are checked with the (possibly new)
 * department, and on update exclude the row being updated, so the same code in another department is
 * legal. The check-then-write race is left to {@code GlobalExceptionHandler}'s
 * {@code DataIntegrityViolationException} mapping to 409; no lock is added. {@code delete} is a soft
 * delete: it flips {@code enabled} to {@code false}.</p>
 *
 * <p>Two rules of the self-referencing {@code reportsToPosition} cannot be expressed by the database
 * (gaps G5/G6) and live here. First, a position may only report to a position of the same company —
 * a reference to another company's position is a 400. Second, the reporting chain must stay acyclic:
 * the candidate parent is rejected when it is the position itself or when the position is reachable
 * by walking {@code reportsToPosition} upward from the candidate parent. The walk uses a visited set
 * rather than trusting the data to be acyclic. The database CHECK
 * ({@code ck_positions_not_self_reporting}) remains the last line of defence for the trivial
 * self-reference, but the service rejects it first so the client gets a 400 instead of a
 * constraint-violation 409.</p>
 *
 * <p>The reads are deliberately <b>not</b> cached. A company-scoped read must run its scope check on
 * <b>every</b> call, and a {@code @Cacheable} on these methods would serve a cache hit without ever
 * reaching {@link CurrentUserContext#verifyCompanyAccess(UUID)}: a caller who knows a company id
 * could read that company's positions after any legitimate read warmed the entry. The neighbouring
 * {@code CompanyRegionService} is the pattern that <em>does</em> cache its company-scoped reads, so
 * the absence of caching here is a decision, not an omission (record T16). The global
 * {@code seniorityLevels} catalog keeps its cache because its reads contain no scope check.</p>
 */
@Service
public class PositionService {

    private static final Logger logger = LoggerFactory.getLogger(PositionService.class);

    private final PositionRepository positionRepository;
    private final DepartmentRepository departmentRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserContext currentUserContext;

    public PositionService(
            PositionRepository positionRepository,
            DepartmentRepository departmentRepository,
            CompanyRepository companyRepository,
            CurrentUserContext currentUserContext) {
        this.positionRepository = positionRepository;
        this.departmentRepository = departmentRepository;
        this.companyRepository = companyRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Verifies company access and then loads the company, in that order.
     *
     * <p>The access check is deliberately the first statement: a caller without access to the company
     * is denied before any row is loaded. This mirrors {@code StoreLocationService.resolveStore},
     * which the record names in E12.</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws CompanyNotFoundException                                when the company does not exist
     */
    private Company resolveCompany(UUID companyId) {
        currentUserContext.verifyCompanyAccess(companyId);
        return companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));
    }

    /**
     * Resolves a department scoped to the company of the path.
     *
     * <p>The lookup is company-scoped, so a department that exists but belongs to another company is
     * reported as not found instead of being accepted.</p>
     *
     * @throws DepartmentNotFoundException when the department does not belong to the company
     */
    private Department resolveDepartment(UUID companyId, UUID departmentId) {
        return departmentRepository
                .findByCompanyIdAndId(companyId, departmentId)
                .orElseThrow(() -> new DepartmentNotFoundException(departmentId));
    }

    /**
     * Resolves the optional reporting parent and enforces the same-company rule (gap G6).
     *
     * @return the parent position, or {@code null} when no parent was requested
     * @throws PositionNotFoundException  when the referenced position does not exist
     * @throws IllegalArgumentException   when the referenced position belongs to another company
     */
    private Position resolveReportsTo(UUID companyId, UUID reportsToPositionId) {
        if (reportsToPositionId == null) {
            return null;
        }

        var reportsTo = positionRepository
                .findById(reportsToPositionId)
                .orElseThrow(() -> new PositionNotFoundException(reportsToPositionId));

        if (!reportsTo.getDepartment().getCompany().getId().equals(companyId)) {
            throw new IllegalArgumentException("A position may only report to a position of the same company");
        }

        return reportsTo;
    }

    /**
     * Rejects a reporting chain that would close a cycle (gap G5).
     *
     * <p>The chain is walked upward from the candidate parent. The position being edited is a cycle
     * when it appears anywhere in that chain — as the parent itself (the trivial self-reference) or
     * as a deeper ancestor. A visited set guards the walk, so a chain that is already cyclic in the
     * database terminates with an error instead of looping.</p>
     *
     * @throws IllegalArgumentException when the candidate parent is the position or one of its
     *     descendants, or when the existing chain is already cyclic
     */
    private void verifyNoCycle(UUID positionId, Position reportsTo) {
        var visited = new HashSet<UUID>();
        Position current = reportsTo;

        while (current != null) {
            if (positionId.equals(current.getId())) {
                throw new IllegalArgumentException("A position cannot report to itself or to one of its descendants");
            }
            if (!visited.add(current.getId())) {
                throw new IllegalArgumentException("The existing reports-to chain already contains a cycle");
            }
            current = current.getReportsToPosition();
        }
    }

    @Transactional(readOnly = true)
    public List<PositionResponse> getAllPositions(UUID companyId, UUID departmentId, boolean includeDisabled) {
        resolveCompany(companyId);

        if (departmentId != null) {
            resolveDepartment(companyId, departmentId);
            if (includeDisabled) {
                return positionRepository.findByDepartmentIdOrderByDisplayOrderAscPositionCodeAsc(departmentId).stream()
                        .map(this::toResponse)
                        .toList();
            }
            return positionRepository
                    .findByDepartmentIdAndEnabledTrueOrderByDisplayOrderAscPositionCodeAsc(departmentId)
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        if (includeDisabled) {
            return positionRepository.findByDepartmentCompanyIdOrderByDisplayOrderAscPositionCodeAsc(companyId).stream()
                    .map(this::toResponse)
                    .toList();
        }
        return positionRepository
                .findByDepartmentCompanyIdAndEnabledTrueOrderByDisplayOrderAscPositionCodeAsc(companyId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PositionResponse getPositionById(UUID companyId, UUID id) {
        resolveCompany(companyId);
        return positionRepository
                .findByDepartmentCompanyIdAndId(companyId, id)
                .map(this::toResponse)
                .orElseThrow(() -> new PositionNotFoundException(id));
    }

    @Transactional
    public PositionResponse createPosition(UUID companyId, PositionRequest request) {
        resolveCompany(companyId);
        var department = resolveDepartment(companyId, request.departmentId());

        if (positionRepository.existsByDepartmentIdAndPositionCode(request.departmentId(), request.positionCode())) {
            throw new DuplicatePositionException("code", request.positionCode());
        }
        if (positionRepository.existsByDepartmentIdAndPositionName(request.departmentId(), request.positionName())) {
            throw new DuplicatePositionException("name", request.positionName());
        }

        // A cycle cannot exist on create, but the parent must exist and belong to the company.
        var reportsTo = resolveReportsTo(companyId, request.reportsToPositionId());

        var position = Position.builder()
                .department(department)
                .positionCode(request.positionCode())
                .positionName(request.positionName())
                .description(request.description())
                .reportsToPosition(reportsTo)
                .displayOrder(request.displayOrder())
                .enabled(request.enabled())
                .build();

        var saved = positionRepository.save(position);
        logger.info(
                "Position created: id={}, companyId={}, departmentId={}, code={}",
                saved.getId(),
                companyId,
                department.getId(),
                saved.getPositionCode());

        return toResponse(saved);
    }

    @Transactional
    public PositionResponse updatePosition(UUID companyId, UUID id, PositionRequest request) {
        resolveCompany(companyId);

        var position = positionRepository
                .findByDepartmentCompanyIdAndId(companyId, id)
                .orElseThrow(() -> new PositionNotFoundException(id));
        var department = resolveDepartment(companyId, request.departmentId());

        // Both uniqueness rules are per department and exclude the row being updated, so a move is
        // checked against the (possibly new) department.
        if (positionRepository.existsByDepartmentIdAndPositionCodeAndIdNot(
                request.departmentId(), request.positionCode(), id)) {
            throw new DuplicatePositionException("code", request.positionCode());
        }
        if (positionRepository.existsByDepartmentIdAndPositionNameAndIdNot(
                request.departmentId(), request.positionName(), id)) {
            throw new DuplicatePositionException("name", request.positionName());
        }

        var reportsTo = resolveReportsTo(companyId, request.reportsToPositionId());
        if (reportsTo != null) {
            verifyNoCycle(id, reportsTo);
        }

        position.setDepartment(department);
        position.setPositionCode(request.positionCode());
        position.setPositionName(request.positionName());
        position.setDescription(request.description());
        position.setReportsToPosition(reportsTo);
        position.setDisplayOrder(request.displayOrder());
        position.setEnabled(request.enabled());

        var updated = positionRepository.save(position);
        logger.info(
                "Position updated: id={}, companyId={}, departmentId={}, code={}",
                updated.getId(),
                companyId,
                department.getId(),
                updated.getPositionCode());

        return toResponse(updated);
    }

    @Transactional
    public void deletePosition(UUID companyId, UUID id) {
        resolveCompany(companyId);

        var position = positionRepository
                .findByDepartmentCompanyIdAndId(companyId, id)
                .orElseThrow(() -> new PositionNotFoundException(id));

        position.setEnabled(false);
        positionRepository.save(position);

        logger.info("Position soft-deleted: id={}, companyId={}, code={}", id, companyId, position.getPositionCode());
    }

    @Transactional
    public PositionResponse setPositionEnabled(UUID companyId, UUID id, boolean enabled) {
        resolveCompany(companyId);

        var position = positionRepository
                .findByDepartmentCompanyIdAndId(companyId, id)
                .orElseThrow(() -> new PositionNotFoundException(id));

        position.setEnabled(enabled);
        var saved = positionRepository.save(position);

        logger.info("Position enabled={}: id={}, companyId={}", enabled, id, companyId);

        return toResponse(saved);
    }

    private PositionResponse toResponse(Position position) {
        var department = position.getDepartment();
        var reportsTo = position.getReportsToPosition();

        return new PositionResponse(
                position.getId(),
                department.getCompany().getId(),
                department.getId(),
                position.getPositionCode(),
                position.getPositionName(),
                position.getDescription(),
                reportsTo == null ? null : reportsTo.getId(),
                position.getDisplayOrder(),
                position.getEnabled(),
                position.getCreatedAt(),
                position.getUpdatedAt());
    }
}
