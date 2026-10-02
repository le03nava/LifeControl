package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.hr.dto.SeniorityLevelRequest;
import com.lifecontrol.api.hr.dto.SeniorityLevelResponse;
import com.lifecontrol.api.hr.exception.DuplicateSeniorityLevelException;
import com.lifecontrol.api.hr.exception.SeniorityLevelNotFoundException;
import com.lifecontrol.api.hr.model.SeniorityLevel;
import com.lifecontrol.api.hr.repository.SeniorityLevelRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the global seniority-level catalog.
 *
 * <p>Seniority levels are global reference data (decision D1), so this service deliberately performs
 * no company-scope check and exposes no company path segment. Every write validates the three
 * natural keys — {@code code}, {@code name} and {@code rank} — and evicts the cache region. The
 * check-then-write race is left to {@code GlobalExceptionHandler}'s {@code DataIntegrityViolationException}
 * mapping to 409; no lock is added. {@code delete} is a soft delete (record E4).</p>
 */
@Service
public class SeniorityLevelService {

    private static final Logger logger = LoggerFactory.getLogger(SeniorityLevelService.class);

    private final SeniorityLevelRepository repository;

    public SeniorityLevelService(SeniorityLevelRepository repository) {
        this.repository = repository;
    }

    @Cacheable(value = "seniorityLevels", key = "'all-' + #includeDisabled")
    @Transactional(readOnly = true)
    public List<SeniorityLevelResponse> getAllSeniorityLevels(boolean includeDisabled) {
        if (includeDisabled) {
            return repository.findAllByOrderByRankAsc().stream()
                    .map(this::toResponse)
                    .toList();
        }
        return repository.findByEnabledTrueOrderByRankAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Cacheable(value = "seniorityLevels", key = "#id")
    @Transactional(readOnly = true)
    public SeniorityLevelResponse getSeniorityLevelById(UUID id) {
        return repository.findById(id).map(this::toResponse).orElseThrow(() -> new SeniorityLevelNotFoundException(id));
    }

    @CacheEvict(value = "seniorityLevels", allEntries = true)
    @Transactional
    public SeniorityLevelResponse createSeniorityLevel(SeniorityLevelRequest request) {
        logger.info("Creating seniority level with code: {}", request.levelCode());

        if (repository.existsByLevelCodeIgnoreCase(request.levelCode())) {
            throw new DuplicateSeniorityLevelException("code", request.levelCode());
        }
        if (repository.existsByLevelNameIgnoreCase(request.levelName())) {
            throw new DuplicateSeniorityLevelException("name", request.levelName());
        }
        if (repository.existsByRank(request.rank())) {
            throw new DuplicateSeniorityLevelException("rank", request.rank());
        }

        var seniorityLevel = toEntity(request);
        var saved = repository.save(seniorityLevel);
        logger.info("Seniority level created successfully with id: {}", saved.getId());

        return toResponse(saved);
    }

    @CacheEvict(value = "seniorityLevels", allEntries = true)
    @Transactional
    public SeniorityLevelResponse updateSeniorityLevel(UUID id, SeniorityLevelRequest request) {
        logger.info("Updating seniority level with id: {}", id);

        var seniorityLevel = repository.findById(id).orElseThrow(() -> new SeniorityLevelNotFoundException(id));

        // Validate uniqueness of code/name/rank excluding the entity being updated.
        var codeOwner = repository.findByLevelCodeIgnoreCase(request.levelCode());
        if (codeOwner.isPresent() && !codeOwner.get().getId().equals(id)) {
            throw new DuplicateSeniorityLevelException("code", request.levelCode());
        }

        var nameOwner = repository.findByLevelNameIgnoreCase(request.levelName());
        if (nameOwner.isPresent() && !nameOwner.get().getId().equals(id)) {
            throw new DuplicateSeniorityLevelException("name", request.levelName());
        }

        var rankOwner = repository.findByRank(request.rank());
        if (rankOwner.isPresent() && !rankOwner.get().getId().equals(id)) {
            throw new DuplicateSeniorityLevelException("rank", request.rank());
        }

        seniorityLevel.setLevelCode(request.levelCode());
        seniorityLevel.setLevelName(request.levelName());
        seniorityLevel.setRank(request.rank());
        seniorityLevel.setEnabled(request.enabled());

        var updated = repository.save(seniorityLevel);
        logger.info("Seniority level updated successfully with id: {}", updated.getId());

        return toResponse(updated);
    }

    @CacheEvict(value = "seniorityLevels", allEntries = true)
    @Transactional
    public void deleteSeniorityLevel(UUID id) {
        logger.info("Soft-deleting seniority level with id: {}", id);

        var seniorityLevel = repository.findById(id).orElseThrow(() -> new SeniorityLevelNotFoundException(id));

        seniorityLevel.setEnabled(false);
        repository.save(seniorityLevel);

        logger.info("Seniority level soft-deleted: id={}, code={}", id, seniorityLevel.getLevelCode());
    }

    @CacheEvict(value = "seniorityLevels", allEntries = true)
    @Transactional
    public SeniorityLevelResponse setSeniorityLevelEnabled(UUID id, boolean enabled) {
        logger.info("Setting seniority level enabled={} for id: {}", enabled, id);

        var seniorityLevel = repository.findById(id).orElseThrow(() -> new SeniorityLevelNotFoundException(id));

        seniorityLevel.setEnabled(enabled);
        var saved = repository.save(seniorityLevel);

        return toResponse(saved);
    }

    private SeniorityLevel toEntity(SeniorityLevelRequest request) {
        return SeniorityLevel.builder()
                .levelCode(request.levelCode())
                .levelName(request.levelName())
                .rank(request.rank())
                .enabled(request.enabled())
                .build();
    }

    private SeniorityLevelResponse toResponse(SeniorityLevel seniorityLevel) {
        return new SeniorityLevelResponse(
                seniorityLevel.getId(),
                seniorityLevel.getLevelCode(),
                seniorityLevel.getLevelName(),
                seniorityLevel.getRank(),
                seniorityLevel.getEnabled(),
                seniorityLevel.getCreatedAt(),
                seniorityLevel.getUpdatedAt());
    }
}
