package com.lifecontrol.api.status.service;

import com.lifecontrol.api.status.dto.StatusTypeRequest;
import com.lifecontrol.api.status.dto.StatusTypeResponse;
import com.lifecontrol.api.status.exception.DuplicateStatusTypeException;
import com.lifecontrol.api.status.exception.StatusTypeNotFoundException;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class StatusTypeService {

    private static final Logger logger = LoggerFactory.getLogger(StatusTypeService.class);

    private final StatusTypeRepository statusTypeRepository;

    public StatusTypeService(StatusTypeRepository statusTypeRepository) {
        this.statusTypeRepository = statusTypeRepository;
    }

    @Cacheable(value = "statusTypes")
    @Transactional(readOnly = true)
    public Page<StatusTypeResponse> getAllStatusTypes(Pageable pageable, String search) {
        if (search != null && !search.isBlank()) {
            return statusTypeRepository.findByEnabledTrueAndStatusTypeNameContainingIgnoreCase(search, pageable)
                    .map(this::toResponse);
        }
        return statusTypeRepository.findByEnabledTrue(pageable)
                .map(this::toResponse);
    }

    @Cacheable(value = "statusTypes", key = "#id")
    @Transactional(readOnly = true)
    public StatusTypeResponse getStatusTypeById(UUID id) {
        return statusTypeRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new StatusTypeNotFoundException(id));
    }

    @CacheEvict(value = "statusTypes", allEntries = true)
    @Transactional
    public StatusTypeResponse createStatusType(StatusTypeRequest request) {
        logger.info("Creating status type with name: {}", request.statusTypeName());

        if (statusTypeRepository.existsByStatusTypeNameIgnoreCase(request.statusTypeName())) {
            throw new DuplicateStatusTypeException(request.statusTypeName());
        }

        var statusType = StatusType.builder()
                .statusTypeName(request.statusTypeName())
                .enabled(true)
                .build();

        var saved = statusTypeRepository.save(statusType);
        logger.info("Status type created successfully with id: {}", saved.getId());

        return toResponse(saved);
    }

    @CacheEvict(value = "statusTypes", allEntries = true)
    @Transactional
    public StatusTypeResponse updateStatusType(UUID id, StatusTypeRequest request) {
        logger.info("Updating status type with id: {}", id);

        var statusType = statusTypeRepository.findById(id)
                .orElseThrow(() -> new StatusTypeNotFoundException(id));

        // Check duplicate name (excluding current entity)
        var existing = statusTypeRepository.findByStatusTypeNameIgnoreCase(request.statusTypeName());
        if (existing.isPresent() && !existing.get().getId().equals(id)) {
            throw new DuplicateStatusTypeException(request.statusTypeName());
        }

        statusType.setStatusTypeName(request.statusTypeName());

        var updated = statusTypeRepository.save(statusType);
        logger.info("Status type updated successfully with id: {}", updated.getId());

        return toResponse(updated);
    }

    @CacheEvict(value = "statusTypes", allEntries = true)
    @Transactional
    public void deleteStatusType(UUID id) {
        logger.info("Soft-deleting status type with id: {}", id);

        var statusType = statusTypeRepository.findById(id)
                .orElseThrow(() -> new StatusTypeNotFoundException(id));

        statusType.setEnabled(false);
        statusTypeRepository.save(statusType);

        logger.info("Status type soft-deleted: id={}, name={}", id, statusType.getStatusTypeName());
    }

    @CacheEvict(value = "statusTypes", allEntries = true)
    @Transactional
    public StatusTypeResponse enableStatusType(UUID id) {
        logger.info("Enabling status type with id: {}", id);

        var statusType = statusTypeRepository.findById(id)
                .orElseThrow(() -> new StatusTypeNotFoundException(id));

        statusType.setEnabled(true);
        var saved = statusTypeRepository.save(statusType);

        return toResponse(saved);
    }

    private StatusTypeResponse toResponse(StatusType statusType) {
        return new StatusTypeResponse(
                statusType.getId(),
                statusType.getStatusTypeName(),
                statusType.getEnabled(),
                statusType.getCreatedAt(),
                statusType.getUpdatedAt()
        );
    }
}
