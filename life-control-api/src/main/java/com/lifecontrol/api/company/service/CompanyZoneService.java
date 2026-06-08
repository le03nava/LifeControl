package com.lifecontrol.api.company.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.dto.CompanyZoneResponse;
import com.lifecontrol.api.company.dto.CreateCompanyZoneRequest;
import com.lifecontrol.api.company.dto.UpdateCompanyZoneRequest;
import com.lifecontrol.api.company.event.CompanyZoneCreatedEvent;
import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.exception.CompanyZoneNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyZoneException;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CompanyZoneService {

    private static final Logger logger = LoggerFactory.getLogger(CompanyZoneService.class);

    private final CompanyZoneRepository companyZoneRepository;
    private final CompanyRegionRepository companyRegionRepository;
    private final CompanyRepository companyRepository;
    private final CompanyCountryRepository companyCountryRepository;
    private final CurrentUserContext currentUserContext;
    private final ApplicationEventPublisher eventPublisher;

    public CompanyZoneService(CompanyZoneRepository companyZoneRepository,
                              CompanyRegionRepository companyRegionRepository,
                              CompanyRepository companyRepository,
                              CompanyCountryRepository companyCountryRepository,
                              CurrentUserContext currentUserContext,
                              ApplicationEventPublisher eventPublisher) {
        this.companyZoneRepository = companyZoneRepository;
        this.companyRegionRepository = companyRegionRepository;
        this.companyRepository = companyRepository;
        this.companyCountryRepository = companyCountryRepository;
        this.currentUserContext = currentUserContext;
        this.eventPublisher = eventPublisher;
    }

    private CompanyRegion resolveCompanyRegion(UUID companyId, UUID companyCountryId, UUID regionId) {
        var companyCountry = companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId)
                .orElseThrow(() -> new CompanyCountryNotFoundException(companyCountryId));
        return companyRegionRepository.findByIdAndCompanyCountryId(regionId, companyCountry.getId())
                .orElseThrow(() -> new CompanyRegionNotFoundException("Company region not found with id: " + regionId));
    }

    @Cacheable(value = "companyZones", key = "'all-' + #regionId + '-' + #includeDisabled")
    @Transactional(readOnly = true)
    public List<CompanyZoneResponse> getAllZones(UUID companyId, UUID companyCountryId, UUID regionId, boolean includeDisabled) {
        currentUserContext.verifyCompanyZoneAccess(companyId, companyCountryId, regionId, null);
        var region = resolveCompanyRegion(companyId, companyCountryId, regionId);

        if (currentUserContext.hasCompanyZoneRole() || currentUserContext.hasCompanyZoneReadRole()) {
            var zoneIds = currentUserContext.getCompanyZoneIds();
            if (zoneIds.isEmpty()) {
                return List.of();
            }
            List<CompanyZone> zones = companyZoneRepository
                    .findByIdInAndCompanyRegionId(zoneIds, region.getId());
            return zones.stream()
                    .filter(z -> includeDisabled || z.getEnabled())
                    .map(this::toResponse)
                    .toList();
        }

        List<CompanyZone> zones = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId());
        return zones.stream()
                .filter(z -> includeDisabled || z.getEnabled())
                .map(this::toResponse)
                .toList();
    }

    @Cacheable(value = "companyZones", key = "#id")
    @Transactional(readOnly = true)
    public CompanyZoneResponse getZoneById(UUID companyId, UUID companyCountryId, UUID regionId, UUID id) {
        currentUserContext.verifyCompanyZoneAccess(companyId, companyCountryId, regionId, id);
        var region = resolveCompanyRegion(companyId, companyCountryId, regionId);
        CompanyZone zone = companyZoneRepository.findByIdAndCompanyRegionId(id, region.getId())
                .orElseThrow(() -> new CompanyZoneNotFoundException("Company zone not found with id: " + id));
        return toResponse(zone);
    }

    @CacheEvict(value = "companyZones", allEntries = true)
    @Transactional
    public CompanyZoneResponse createZone(UUID companyId, UUID companyCountryId, UUID regionId, CreateCompanyZoneRequest request) {
        if (currentUserContext.hasCompanyZoneRole()) {
            throw new AccessDeniedException("Zone-scoped users cannot create zones");
        }
        currentUserContext.verifyCompanyZoneAccess(companyId, companyCountryId, regionId, null);
        companyRepository.findById(companyId)
                .orElseThrow(() -> new CompanyNotFoundException(companyId));

        var region = resolveCompanyRegion(companyId, companyCountryId, regionId);

        if (companyZoneRepository.existsByCompanyRegionIdAndZoneCode(region.getId(), request.zoneCode())) {
            throw new DuplicateCompanyZoneException(
                    "Company zone with code '" + request.zoneCode() + "' already exists for this region");
        }

        CompanyZone zone = CompanyZone.builder()
                .companyRegion(region)
                .zoneCode(request.zoneCode())
                .zoneName(request.zoneName())
                .description(request.description())
                .displayOrder(request.displayOrder())
                .enabled(true)
                .build();

        CompanyZone saved = companyZoneRepository.save(zone);
        eventPublisher.publishEvent(new CompanyZoneCreatedEvent(
                this, saved.getId(), companyId, saved.getZoneName(),
                region.getRegionName()));
        logger.info("CompanyZone created: code={}, regionId={}", saved.getZoneCode(), region.getId());
        return toResponse(saved);
    }

    @CacheEvict(value = "companyZones", allEntries = true)
    @Transactional
    public CompanyZoneResponse updateZone(UUID companyId, UUID companyCountryId, UUID regionId, UUID id, UpdateCompanyZoneRequest request) {
        currentUserContext.verifyCompanyZoneAccess(companyId, companyCountryId, regionId, id);
        companyRepository.findById(companyId)
                .orElseThrow(() -> new CompanyNotFoundException(companyId));

        var region = resolveCompanyRegion(companyId, companyCountryId, regionId);

        CompanyZone zone = companyZoneRepository.findByIdAndCompanyRegionId(id, region.getId())
                .orElseThrow(() -> new CompanyZoneNotFoundException("Company zone not found with id: " + id));

        boolean codeChanged = !zone.getZoneCode().equals(request.zoneCode());
        if (codeChanged && companyZoneRepository.existsByCompanyRegionIdAndZoneCodeAndIdNot(
                region.getId(), request.zoneCode(), id)) {
            throw new DuplicateCompanyZoneException(
                    "Company zone with code '" + request.zoneCode() + "' already exists for this region");
        }

        zone.setZoneCode(request.zoneCode());
        zone.setZoneName(request.zoneName());
        zone.setDescription(request.description());
        zone.setDisplayOrder(request.displayOrder());

        CompanyZone saved = companyZoneRepository.save(zone);
        logger.info("CompanyZone updated: id={}, code={}", saved.getId(), saved.getZoneCode());
        return toResponse(saved);
    }

    @CacheEvict(value = "companyZones", allEntries = true)
    @Transactional
    public void deleteZone(UUID companyId, UUID companyCountryId, UUID regionId, UUID id) {
        currentUserContext.verifyCompanyZoneAccess(companyId, companyCountryId, regionId, id);
        companyRepository.findById(companyId)
                .orElseThrow(() -> new CompanyNotFoundException(companyId));

        var region = resolveCompanyRegion(companyId, companyCountryId, regionId);

        CompanyZone zone = companyZoneRepository.findByIdAndCompanyRegionId(id, region.getId())
                .orElseThrow(() -> new CompanyZoneNotFoundException("Company zone not found with id: " + id));

        zone.setEnabled(false);
        companyZoneRepository.save(zone);

        logger.info("CompanyZone soft-deleted: id={}, code={}", id, zone.getZoneCode());
    }

    @CacheEvict(value = "companyZones", allEntries = true)
    @Transactional
    public CompanyZoneResponse enableZone(UUID companyId, UUID companyCountryId, UUID regionId, UUID id) {
        currentUserContext.verifyCompanyZoneAccess(companyId, companyCountryId, regionId, id);
        companyRepository.findById(companyId)
                .orElseThrow(() -> new CompanyNotFoundException(companyId));

        var region = resolveCompanyRegion(companyId, companyCountryId, regionId);

        CompanyZone zone = companyZoneRepository.findByIdAndCompanyRegionId(id, region.getId())
                .orElseThrow(() -> new CompanyZoneNotFoundException("Company zone not found with id: " + id));

        zone.setEnabled(true);
        CompanyZone saved = companyZoneRepository.save(zone);

        logger.info("CompanyZone re-enabled: id={}, code={}", id, saved.getZoneCode());
        return toResponse(saved);
    }

    private CompanyZoneResponse toResponse(CompanyZone zone) {
        return new CompanyZoneResponse(
                zone.getId(),
                zone.getCompanyRegion().getId(),
                zone.getCompanyRegion().getCompanyCountry().getId(),
                zone.getCompanyRegion().getCompanyCountry().getCompany().getId(),
                zone.getCompanyRegion().getCompanyCountry().getCountry().getId(),
                zone.getZoneCode(),
                zone.getZoneName(),
                zone.getDescription(),
                zone.getDisplayOrder(),
                zone.getEnabled(),
                zone.getCreatedAt(),
                zone.getUpdatedAt()
        );
    }
}
