package com.lifecontrol.api.measureunit.service;

import com.lifecontrol.api.measureunit.dto.MeasureUnitRequest;
import com.lifecontrol.api.measureunit.dto.MeasureUnitResponse;
import com.lifecontrol.api.measureunit.exception.DuplicateMeasureUnitException;
import com.lifecontrol.api.measureunit.exception.MeasureUnitNotFoundException;
import com.lifecontrol.api.measureunit.model.MeasureUnit;
import com.lifecontrol.api.measureunit.model.MeasureUnitType;
import com.lifecontrol.api.measureunit.repository.MeasureUnitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class MeasureUnitService {

    private static final Logger logger = LoggerFactory.getLogger(MeasureUnitService.class);

    private final MeasureUnitRepository measureUnitRepository;

    public MeasureUnitService(MeasureUnitRepository measureUnitRepository) {
        this.measureUnitRepository = measureUnitRepository;
    }

    @Cacheable(value = "measureUnits", key = "'all-' + #includeDisabled")
    @Transactional(readOnly = true)
    public List<MeasureUnitResponse> getAllMeasureUnits(boolean includeDisabled) {
        if (includeDisabled) {
            return measureUnitRepository.findAll().stream()
                    .map(this::toResponse)
                    .toList();
        }
        return measureUnitRepository.findByEnabledTrue().stream()
                .map(this::toResponse)
                .toList();
    }

    @Cacheable(value = "measureUnits", key = "#id")
    @Transactional(readOnly = true)
    public MeasureUnitResponse getMeasureUnitById(UUID id) {
        return measureUnitRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new MeasureUnitNotFoundException(id));
    }

    @Cacheable(value = "measureUnits", key = "'type-' + #unitType")
    @Transactional(readOnly = true)
    public List<MeasureUnitResponse> getMeasureUnitsByType(String unitType) {
        var type = MeasureUnitType.valueOf(unitType);
        return measureUnitRepository.findByUnitTypeAndEnabledTrue(type).stream()
                .map(this::toResponse)
                .toList();
    }

    @CacheEvict(value = "measureUnits", allEntries = true)
    @Transactional
    public MeasureUnitResponse createMeasureUnit(MeasureUnitRequest request) {
        logger.info("Creating measure unit with SAT code: {}", request.satCode());

        if (measureUnitRepository.existsBySatCode(request.satCode())) {
            throw new DuplicateMeasureUnitException(
                    "Ya existe una unidad de medida con código SAT: " + request.satCode());
        }

        var type = MeasureUnitType.valueOf(request.unitType());

        var measureUnit = MeasureUnit.builder()
                .measureUnitName(request.measureUnitName())
                .measureUnitShortName(request.measureUnitShortName())
                .unitType(type)
                .satCode(request.satCode())
                .description(request.description())
                .enabled(true)
                .build();

        var saved = measureUnitRepository.save(measureUnit);
        logger.info("Measure unit created successfully with id: {}", saved.getId());

        return toResponse(saved);
    }

    @CacheEvict(value = "measureUnits", allEntries = true)
    @Transactional
    public MeasureUnitResponse updateMeasureUnit(UUID id, MeasureUnitRequest request) {
        logger.info("Updating measure unit with id: {}", id);

        var measureUnit = measureUnitRepository.findById(id)
                .orElseThrow(() -> new MeasureUnitNotFoundException(id));

        // Validate uniqueness of satCode (excluding current)
        if (!measureUnit.getSatCode().equals(request.satCode())
                && measureUnitRepository.existsBySatCode(request.satCode())) {
            throw new DuplicateMeasureUnitException(
                    "Ya existe una unidad de medida con código SAT: " + request.satCode());
        }

        var type = MeasureUnitType.valueOf(request.unitType());

        measureUnit.setMeasureUnitName(request.measureUnitName());
        measureUnit.setMeasureUnitShortName(request.measureUnitShortName());
        measureUnit.setUnitType(type);
        measureUnit.setSatCode(request.satCode());
        measureUnit.setDescription(request.description());

        var updated = measureUnitRepository.save(measureUnit);
        logger.info("Measure unit updated successfully with id: {}", updated.getId());

        return toResponse(updated);
    }

    @CacheEvict(value = "measureUnits", allEntries = true)
    @Transactional
    public void deleteMeasureUnit(UUID id) {
        logger.info("Soft-deleting measure unit with id: {}", id);

        var measureUnit = measureUnitRepository.findById(id)
                .orElseThrow(() -> new MeasureUnitNotFoundException(id));

        measureUnit.setEnabled(false);
        measureUnitRepository.save(measureUnit);

        logger.info("Measure unit soft-deleted: id={}, satCode={}", id, measureUnit.getSatCode());
    }

    @CacheEvict(value = "measureUnits", allEntries = true)
    @Transactional
    public MeasureUnitResponse enableMeasureUnit(UUID id) {
        logger.info("Enabling measure unit with id: {}", id);

        var measureUnit = measureUnitRepository.findById(id)
                .orElseThrow(() -> new MeasureUnitNotFoundException(id));

        if (measureUnit.getEnabled()) {
            logger.warn("Measure unit is already enabled: id={}", id);
            return toResponse(measureUnit);
        }

        measureUnit.setEnabled(true);
        var saved = measureUnitRepository.save(measureUnit);
        logger.info("Measure unit enabled: id={}, satCode={}", id, saved.getSatCode());

        return toResponse(saved);
    }

    private MeasureUnitResponse toResponse(MeasureUnit measureUnit) {
        return new MeasureUnitResponse(
                measureUnit.getId(),
                measureUnit.getMeasureUnitName(),
                measureUnit.getMeasureUnitShortName(),
                measureUnit.getUnitType().name(),
                measureUnit.getSatCode(),
                measureUnit.getDescription(),
                measureUnit.getEnabled(),
                measureUnit.getCreatedAt(),
                measureUnit.getUpdatedAt()
        );
    }
}
