package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.dto.PositionSalaryBandRequest;
import com.lifecontrol.api.hr.dto.PositionSalaryBandResponse;
import com.lifecontrol.api.hr.dto.PositionSalaryBandsRequest;
import com.lifecontrol.api.hr.exception.PositionNotFoundException;
import com.lifecontrol.api.hr.exception.SeniorityLevelNotFoundException;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.model.PositionSalaryBand;
import com.lifecontrol.api.hr.model.SeniorityLevel;
import com.lifecontrol.api.hr.repository.PositionRepository;
import com.lifecontrol.api.hr.repository.PositionSalaryBandRepository;
import com.lifecontrol.api.hr.repository.SeniorityLevelRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the salary bands of a company-scoped position.
 *
 * <p>Every public method starts by resolving the company through {@link #resolveCompany(UUID)},
 * whose first statement is {@link CurrentUserContext#verifyCompanyAccess(UUID)} — the company-scope
 * contract (record E12). The position is then resolved through a {@code companyId}-scoped query, so
 * a position of another company is a {@link PositionNotFoundException} (404), exactly as
 * {@code PositionService} does.</p>
 *
 * <p>The save is a <b>full-set upsert</b> on the natural key {@code (position_id, seniority_level_id)}
 * (decision T10): the request is the position's complete set of bands, a submitted key is inserted
 * when missing and updated in place when present, and a stored key the request omits is
 * <b>disabled</b>, never deleted (decision D13). There is no DELETE endpoint for this resource: the
 * only way to clear a band is the omission rule, and it keeps the row so the read can distinguish
 * "explicitly cleared" from "never configured".</p>
 *
 * <p>Body rules the database would otherwise answer with a constraint violation are rejected here as
 * a 400 through {@link IllegalArgumentException}: a negative {@code minimumSalary}, a
 * {@code maximumSalary} below the minimum, and a duplicated {@code seniorityLevelId} inside one
 * request (rejected explicitly instead of last-wins). A reference that does not resolve is a 404
 * ({@link SeniorityLevelNotFoundException}), matching the {@code PositionService.resolveDepartment}
 * precedent.</p>
 *
 * <p>The reads are deliberately <b>not</b> cached (record T16): a {@code @Cacheable} would serve a
 * cache hit without ever running {@link CurrentUserContext#verifyCompanyAccess(UUID)}, trading the
 * scope check for a lookup. A proxy-based test pins that.</p>
 */
@Service
public class PositionSalaryBandService {

    private static final Logger logger = LoggerFactory.getLogger(PositionSalaryBandService.class);

    private final PositionSalaryBandRepository salaryBandRepository;
    private final PositionRepository positionRepository;
    private final SeniorityLevelRepository seniorityLevelRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserContext currentUserContext;

    public PositionSalaryBandService(
            PositionSalaryBandRepository salaryBandRepository,
            PositionRepository positionRepository,
            SeniorityLevelRepository seniorityLevelRepository,
            CompanyRepository companyRepository,
            CurrentUserContext currentUserContext) {
        this.salaryBandRepository = salaryBandRepository;
        this.positionRepository = positionRepository;
        this.seniorityLevelRepository = seniorityLevelRepository;
        this.companyRepository = companyRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Verifies company access and then loads the company, in that order.
     *
     * <p>The access check is deliberately the first statement: a caller without access to the company
     * is denied before any row is loaded (record E12).</p>
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
     * Resolves the position scoped to the company of the path.
     *
     * <p>The lookup is company-scoped, so a position that exists but belongs to another company is
     * reported as not found instead of being accepted.</p>
     *
     * @throws PositionNotFoundException when the position does not belong to the company
     */
    private Position resolvePosition(UUID companyId, UUID positionId) {
        return positionRepository
                .findByDepartmentCompanyIdAndId(companyId, positionId)
                .orElseThrow(() -> new PositionNotFoundException(positionId));
    }

    /**
     * Returns every stored band of the position, disabled rows included, ordered by seniority rank.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws CompanyNotFoundException                                when the company does not exist
     * @throws PositionNotFoundException                               when the position does not belong to the company
     */
    @Transactional(readOnly = true)
    public List<PositionSalaryBandResponse> getSalaryBands(UUID companyId, UUID positionId) {
        resolveCompany(companyId);
        resolvePosition(companyId, positionId);

        return salaryBandRepository.findByPositionIdOrderBySeniorityLevelRankAsc(positionId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Replaces the position's complete set of bands with the request's set.
     *
     * <p>Every submitted key is upserted on {@code (position_id, seniority_level_id)}; every stored
     * key the request omits is disabled and kept. The whole save runs in one transaction.</p>
     *
     * @return the complete stored set after the save, disabled rows included
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws CompanyNotFoundException                                when the company does not exist
     * @throws PositionNotFoundException                               when the position does not belong to the company
     * @throws SeniorityLevelNotFoundException                         when a submitted level does not exist
     * @throws IllegalArgumentException                                when a range is invalid or a level is duplicated (400)
     */
    @Transactional
    public List<PositionSalaryBandResponse> replaceSalaryBands(
            UUID companyId, UUID positionId, PositionSalaryBandsRequest request) {
        resolveCompany(companyId);
        var position = resolvePosition(companyId, positionId);

        // The full stored set is the source for the omission rule; the result map is seeded with it so
        // the response carries disabled rows too and matches the read ordering.
        var stored = salaryBandRepository.findByPositionIdOrderBySeniorityLevelRankAsc(positionId);
        var result = new LinkedHashMap<UUID, PositionSalaryBand>();
        stored.forEach(band -> result.put(band.getSeniorityLevel().getId(), band));

        // Shape validation first, so a bad request is rejected before any write.
        var submittedLevels = new LinkedHashSet<UUID>();
        for (var item : request.bands()) {
            if (!submittedLevels.add(item.seniorityLevelId())) {
                throw new IllegalArgumentException(
                        "Duplicate seniorityLevelId in salary bands: " + item.seniorityLevelId());
            }
            validateRange(item);
        }

        // Resolve every reference before the write loop, so a missing level is a 404 and can never
        // leave a partial save behind.
        var resolvedLevels = new LinkedHashMap<UUID, SeniorityLevel>();
        for (var item : request.bands()) {
            var levelId = item.seniorityLevelId();
            if (!resolvedLevels.containsKey(levelId)) {
                resolvedLevels.put(
                        levelId,
                        seniorityLevelRepository
                                .findById(levelId)
                                .orElseThrow(() -> new SeniorityLevelNotFoundException(levelId)));
            }
        }

        for (var item : request.bands()) {
            var levelId = item.seniorityLevelId();

            // Upsert on the natural key: present -> update in place (same row), absent -> insert.
            var band = salaryBandRepository
                    .findByPositionIdAndSeniorityLevelId(positionId, levelId)
                    .orElseGet(() -> PositionSalaryBand.builder()
                            .position(position)
                            .seniorityLevel(resolvedLevels.get(levelId))
                            .enabled(true)
                            .build());

            band.setMinimumSalary(item.minimumSalary());
            band.setMaximumSalary(item.maximumSalary());
            band.setEnabled(true);

            result.put(levelId, salaryBandRepository.save(band));
        }

        // Omission means "cleared": disable, never delete. The row stays for the read contract.
        var disabled = 0;
        for (var band : stored) {
            if (!submittedLevels.contains(band.getSeniorityLevel().getId())) {
                band.setEnabled(false);
                salaryBandRepository.save(band);
                disabled++;
            }
        }

        logger.info(
                "Position salary bands replaced: positionId={}, companyId={}, submitted={}, disabled={}",
                positionId,
                companyId,
                submittedLevels.size(),
                disabled);

        return result.values().stream()
                .sorted(Comparator.comparing(band -> band.getSeniorityLevel().getRank()))
                .map(this::toResponse)
                .toList();
    }

    /**
     * Rejects a band the database would accept but the product must not, as a 400 naming the rule.
     */
    private void validateRange(PositionSalaryBandRequest band) {
        var minimum = band.minimumSalary();
        var maximum = band.maximumSalary();

        if (minimum.signum() < 0) {
            throw new IllegalArgumentException("minimumSalary must be greater than or equal to 0");
        }
        if (maximum.compareTo(minimum) < 0) {
            throw new IllegalArgumentException("maximumSalary must be greater than or equal to minimumSalary");
        }
    }

    private PositionSalaryBandResponse toResponse(PositionSalaryBand band) {
        return new PositionSalaryBandResponse(
                band.getId(),
                band.getPosition().getId(),
                band.getSeniorityLevel().getId(),
                band.getMinimumSalary(),
                band.getMaximumSalary(),
                band.getEnabled(),
                band.getCreatedAt(),
                band.getUpdatedAt());
    }
}
