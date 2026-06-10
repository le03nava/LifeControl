package com.lifecontrol.api.shift.service;

import com.lifecontrol.api.shift.dto.ShiftRequest;
import com.lifecontrol.api.shift.dto.ShiftResponse;
import com.lifecontrol.api.shift.exception.ShiftAlreadyOpenException;
import com.lifecontrol.api.shift.exception.ShiftNotFoundException;
import com.lifecontrol.api.shift.exception.ShiftNotOpenException;
import com.lifecontrol.api.shift.model.Shift;
import com.lifecontrol.api.shift.repository.ShiftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ShiftService {

    private static final Logger logger = LoggerFactory.getLogger(ShiftService.class);

    private static final String STATUS_ABIERTO = "ABIERTO";
    private static final String STATUS_CERRADO = "CERRADO";

    private final ShiftRepository shiftRepository;

    public ShiftService(ShiftRepository shiftRepository) {
        this.shiftRepository = shiftRepository;
    }

    // ─── CRUD ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ShiftResponse> getAllShifts(Pageable pageable) {
        return shiftRepository.findByEnabledTrueOrderByOpenedAtDesc(pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ShiftResponse getShiftById(UUID id) {
        var shift = shiftRepository.findById(id)
                .orElseThrow(() -> new ShiftNotFoundException(id));
        return toResponse(shift);
    }

    @Transactional
    public ShiftResponse createShift(ShiftRequest request) {
        logger.info("Creating shift: companyStoreId={}, userId={}", request.companyStoreId(), request.userId());

        var shift = Shift.builder()
                .companyStoreId(request.companyStoreId())
                .userId(request.userId())
                .openedAt(LocalDateTime.now())
                .status(STATUS_ABIERTO)
                .enabled(request.enabled())
                .build();

        var saved = shiftRepository.save(shift);
        logger.info("Shift created: id={}, status={}", saved.getId(), saved.getStatus());

        return toResponse(saved);
    }

    @Transactional
    public ShiftResponse updateShift(UUID id, ShiftRequest request) {
        logger.info("Updating shift: id={}", id);

        var shift = shiftRepository.findById(id)
                .orElseThrow(() -> new ShiftNotFoundException(id));

        shift.setCompanyStoreId(request.companyStoreId());
        shift.setUserId(request.userId());
        shift.setEnabled(request.enabled());

        var updated = shiftRepository.save(shift);
        logger.info("Shift updated: id={}", id);

        return toResponse(updated);
    }

    @Transactional
    public void deleteShift(UUID id) {
        logger.info("Soft-deleting shift: id={}", id);

        var shift = shiftRepository.findById(id)
                .orElseThrow(() -> new ShiftNotFoundException(id));

        shift.setEnabled(false);
        shiftRepository.save(shift);
        logger.info("Shift soft-deleted: id={}", id);
    }

    @Transactional
    public ShiftResponse enableShift(UUID id) {
        logger.info("Re-enabling shift: id={}", id);

        var shift = shiftRepository.findById(id)
                .orElseThrow(() -> new ShiftNotFoundException(id));

        shift.setEnabled(true);
        var saved = shiftRepository.save(shift);

        return toResponse(saved);
    }

    // ─── Shift Lifecycle ─────────────────────────────────────────────────

    @Transactional
    public ShiftResponse openShift(UUID companyStoreId, String userId) {
        logger.info("Opening shift: companyStoreId={}, userId={}", companyStoreId, userId);

        // Validate no open shift exists for the store
        var existingOpen = shiftRepository.findOpenShiftByStoreId(companyStoreId);
        if (existingOpen.isPresent()) {
            throw new ShiftAlreadyOpenException(companyStoreId);
        }

        var now = LocalDateTime.now();
        var shift = Shift.builder()
                .companyStoreId(companyStoreId)
                .userId(userId)
                .openedAt(now)
                .status(STATUS_ABIERTO)
                .enabled(true)
                .build();

        var saved = shiftRepository.save(shift);
        logger.info("Shift opened: id={}, companyStoreId={}, userId={}, openedAt={}",
                saved.getId(), companyStoreId, userId, now);

        return toResponse(saved);
    }

    @Transactional
    public ShiftResponse closeShift(UUID shiftId) {
        logger.info("Closing shift: id={}", shiftId);

        var shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new ShiftNotFoundException(shiftId));

        if (!STATUS_ABIERTO.equals(shift.getStatus())) {
            throw new ShiftNotOpenException(shiftId, shift.getStatus());
        }

        shift.setClosedAt(LocalDateTime.now());
        shift.setStatus(STATUS_CERRADO);
        var saved = shiftRepository.save(shift);

        logger.info("Shift closed: id={}, closedAt={}", shiftId, saved.getClosedAt());

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ShiftResponse> getOpenShifts() {
        return shiftRepository.findAllOpenShifts().stream()
                .map(this::toResponse)
                .toList();
    }

    // ─── Response Mapper ─────────────────────────────────────────────────

    private ShiftResponse toResponse(Shift shift) {
        return new ShiftResponse(
                shift.getId(),
                shift.getCompanyStoreId(),
                shift.getUserId(),
                shift.getOpenedAt(),
                shift.getClosedAt(),
                shift.getStatus(),
                shift.getEnabled(),
                shift.getCreatedAt(),
                shift.getUpdatedAt()
        );
    }
}
