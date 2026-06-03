package com.lifecontrol.api.status.service;

import com.lifecontrol.api.status.dto.StatusRequest;
import com.lifecontrol.api.status.dto.StatusResponse;
import com.lifecontrol.api.status.exception.DuplicateStatusException;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.exception.StatusTypeNotFoundException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.status.repository.StatusTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class StatusService {

    private static final Logger logger = LoggerFactory.getLogger(StatusService.class);

    private final StatusRepository statusRepository;
    private final StatusTypeRepository statusTypeRepository;

    public StatusService(StatusRepository statusRepository,
                         StatusTypeRepository statusTypeRepository) {
        this.statusRepository = statusRepository;
        this.statusTypeRepository = statusTypeRepository;
    }

    @Cacheable(value = "statuses")
    @Transactional(readOnly = true)
    public List<StatusResponse> getStatusesByTypeId(UUID statusTypeId) {
        if (!statusTypeRepository.existsById(statusTypeId)) {
            throw new StatusTypeNotFoundException(statusTypeId);
        }

        return statusRepository.findByStatusTypeId(statusTypeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Cacheable(value = "statuses", key = "#id")
    @Transactional(readOnly = true)
    public StatusResponse getStatusById(UUID id) {
        return statusRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new StatusNotFoundException(id));
    }

    @Cacheable(value = "statuses", key = "#id")
    @Transactional(readOnly = true)
    public StatusResponse getStatusByIdAndTypeId(UUID id, UUID statusTypeId) {
        return statusRepository.findByIdAndStatusTypeId(id, statusTypeId)
                .map(this::toResponse)
                .orElseThrow(() -> new StatusNotFoundException(id));
    }

    @CacheEvict(value = "statuses", allEntries = true)
    @Transactional
    public StatusResponse createStatus(StatusRequest request) {
        logger.info("Creating status with name: {} for status type: {}", request.statusName(), request.statusTypeId());

        var statusType = statusTypeRepository.findById(request.statusTypeId())
                .orElseThrow(() -> new StatusTypeNotFoundException(request.statusTypeId()));

        if (statusRepository.existsByStatusNameIgnoreCaseAndStatusTypeId(request.statusName(), request.statusTypeId())) {
            throw new DuplicateStatusException(request.statusName());
        }

        var status = Status.builder()
                .statusName(request.statusName())
                .statusType(statusType)
                .enabled(true)
                .build();

        var saved = statusRepository.save(status);
        logger.info("Status created successfully with id: {}", saved.getId());

        return toResponse(saved);
    }

    @CacheEvict(value = "statuses", allEntries = true)
    @Transactional
    public StatusResponse updateStatus(UUID id, StatusRequest request) {
        logger.info("Updating status with id: {}", id);

        var status = statusRepository.findById(id)
                .orElseThrow(() -> new StatusNotFoundException(id));

        // Verify new parent status type exists if it changed
        if (!status.getStatusType().getId().equals(request.statusTypeId())) {
            statusTypeRepository.findById(request.statusTypeId())
                    .orElseThrow(() -> new StatusTypeNotFoundException(request.statusTypeId()));
        }

        // Check duplicate name within same type (excluding current entity)
        if (!status.getStatusName().equals(request.statusName())) {
            if (statusRepository.existsByStatusNameIgnoreCaseAndStatusTypeId(request.statusName(), request.statusTypeId())) {
                throw new DuplicateStatusException(request.statusName());
            }
        }

        status.setStatusName(request.statusName());
        if (!status.getStatusType().getId().equals(request.statusTypeId())) {
            var newType = statusTypeRepository.getReferenceById(request.statusTypeId());
            status.setStatusType(newType);
        }

        var updated = statusRepository.save(status);
        logger.info("Status updated successfully with id: {}", updated.getId());

        return toResponse(updated);
    }

    @CacheEvict(value = "statuses", allEntries = true)
    @Transactional
    public void deleteStatus(UUID id) {
        logger.info("Soft-deleting status with id: {}", id);

        var status = statusRepository.findById(id)
                .orElseThrow(() -> new StatusNotFoundException(id));

        status.setEnabled(false);
        statusRepository.save(status);

        logger.info("Status soft-deleted: id={}, name={}", id, status.getStatusName());
    }

    @CacheEvict(value = "statuses", allEntries = true)
    @Transactional
    public StatusResponse enableStatus(UUID id) {
        logger.info("Enabling status with id: {}", id);

        var status = statusRepository.findById(id)
                .orElseThrow(() -> new StatusNotFoundException(id));

        status.setEnabled(true);
        var saved = statusRepository.save(status);

        return toResponse(saved);
    }

    private StatusResponse toResponse(Status status) {
        return new StatusResponse(
                status.getId(),
                status.getStatusName(),
                status.getStatusType().getId(),
                status.getStatusType().getStatusTypeName(),
                status.getEnabled(),
                status.getCreatedAt(),
                status.getUpdatedAt()
        );
    }
}
