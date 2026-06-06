package com.lifecontrol.api.company.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.dto.CompanyRegionResponse;
import com.lifecontrol.api.company.dto.CreateCompanyRegionRequest;
import com.lifecontrol.api.company.dto.UpdateCompanyRegionRequest;
import com.lifecontrol.api.company.event.CompanyRegionCreatedEvent;
import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyRegionException;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CompanyRegionService {

    private static final Logger logger = LoggerFactory.getLogger(CompanyRegionService.class);

    private final CompanyRegionRepository companyRegionRepository;
    private final CompanyRepository companyRepository;
    private final CompanyCountryRepository companyCountryRepository;
    private final CurrentUserContext currentUserContext;
    private final ApplicationEventPublisher eventPublisher;

    public CompanyRegionService(CompanyRegionRepository companyRegionRepository,
                                 CompanyRepository companyRepository,
                                 CompanyCountryRepository companyCountryRepository,
                                 CurrentUserContext currentUserContext,
                                 ApplicationEventPublisher eventPublisher) {
        this.companyRegionRepository = companyRegionRepository;
        this.companyRepository = companyRepository;
        this.companyCountryRepository = companyCountryRepository;
        this.currentUserContext = currentUserContext;
        this.eventPublisher = eventPublisher;
    }

    private CompanyCountry resolveCompanyCountry(UUID companyId, UUID companyCountryId) {
        return companyCountryRepository.findByCompanyIdAndId(companyId, companyCountryId)
                .orElseThrow(() -> new CompanyCountryNotFoundException(companyCountryId));
    }

    @Cacheable(value = "companyRegions", key = "'all-' + #companyCountryId + '-' + #includeDisabled")
    @Transactional(readOnly = true)
    public List<CompanyRegionResponse> getAllRegions(UUID companyId, UUID companyCountryId, boolean includeDisabled) {
        currentUserContext.verifyCompanyAccess(companyId);
        var companyCountry = resolveCompanyCountry(companyId, companyCountryId);
        List<CompanyRegion> regions = companyRegionRepository
                .findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId());
        return regions.stream()
                .filter(r -> includeDisabled || r.getEnabled())
                .map(this::toResponse)
                .toList();
    }

    @Cacheable(value = "companyRegions", key = "#id")
    @Transactional(readOnly = true)
    public CompanyRegionResponse getRegionById(UUID companyId, UUID companyCountryId, UUID id) {
        currentUserContext.verifyCompanyAccess(companyId);
        var companyCountry = resolveCompanyCountry(companyId, companyCountryId);
        CompanyRegion region = companyRegionRepository.findByIdAndCompanyCountryId(id, companyCountry.getId())
                .orElseThrow(() -> new CompanyRegionNotFoundException("Company region not found with id: " + id));
        return toResponse(region);
    }

    @CacheEvict(value = "companyRegions", allEntries = true)
    @Transactional
    public CompanyRegionResponse createRegion(UUID companyId, UUID companyCountryId, CreateCompanyRegionRequest request) {
        currentUserContext.verifyCompanyAccess(companyId);
        companyRepository.findById(companyId)
                .orElseThrow(() -> new CompanyNotFoundException(companyId));

        var companyCountry = resolveCompanyCountry(companyId, companyCountryId);

        if (companyRegionRepository.existsByCompanyCountryIdAndRegionCode(companyCountry.getId(), request.regionCode())) {
            throw new DuplicateCompanyRegionException(
                    "Company region with code '" + request.regionCode() + "' already exists for this country");
        }

        CompanyRegion region = CompanyRegion.builder()
                .companyCountry(companyCountry)
                .regionCode(request.regionCode())
                .regionName(request.regionName())
                .enabled(true)
                .build();

        CompanyRegion saved = companyRegionRepository.save(region);

        eventPublisher.publishEvent(new CompanyRegionCreatedEvent(
                this, saved.getId(), companyId, saved.getRegionName(),
                companyCountry.getCountry().getCountryName()));

        logger.info("CompanyRegion created: code={}, companyCountryId={}", saved.getRegionCode(), companyCountry.getId());
        return toResponse(saved);
    }

    @CacheEvict(value = "companyRegions", allEntries = true)
    @Transactional
    public CompanyRegionResponse updateRegion(UUID companyId, UUID companyCountryId, UUID id, UpdateCompanyRegionRequest request) {
        currentUserContext.verifyCompanyAccess(companyId);
        companyRepository.findById(companyId)
                .orElseThrow(() -> new CompanyNotFoundException(companyId));

        var companyCountry = resolveCompanyCountry(companyId, companyCountryId);

        CompanyRegion region = companyRegionRepository.findByIdAndCompanyCountryId(id, companyCountry.getId())
                .orElseThrow(() -> new CompanyRegionNotFoundException("Company region not found with id: " + id));

        boolean codeChanged = !region.getRegionCode().equals(request.regionCode());
        if (codeChanged && companyRegionRepository.existsByCompanyCountryIdAndRegionCodeAndIdNot(
                companyCountry.getId(), request.regionCode(), id)) {
            throw new DuplicateCompanyRegionException(
                    "Company region with code '" + request.regionCode() + "' already exists for this country");
        }

        region.setRegionCode(request.regionCode());
        region.setRegionName(request.regionName());

        CompanyRegion saved = companyRegionRepository.save(region);
        logger.info("CompanyRegion updated: id={}, code={}", saved.getId(), saved.getRegionCode());
        return toResponse(saved);
    }

    @CacheEvict(value = "companyRegions", allEntries = true)
    @Transactional
    public void deleteRegion(UUID companyId, UUID companyCountryId, UUID id) {
        currentUserContext.verifyCompanyAccess(companyId);
        companyRepository.findById(companyId)
                .orElseThrow(() -> new CompanyNotFoundException(companyId));

        var companyCountry = resolveCompanyCountry(companyId, companyCountryId);

        CompanyRegion region = companyRegionRepository.findByIdAndCompanyCountryId(id, companyCountry.getId())
                .orElseThrow(() -> new CompanyRegionNotFoundException("Company region not found with id: " + id));

        region.setEnabled(false);
        companyRegionRepository.save(region);

        logger.info("CompanyRegion soft-deleted: id={}, code={}", id, region.getRegionCode());
    }

    @CacheEvict(value = "companyRegions", allEntries = true)
    @Transactional
    public CompanyRegionResponse enableRegion(UUID companyId, UUID companyCountryId, UUID id) {
        currentUserContext.verifyCompanyAccess(companyId);
        companyRepository.findById(companyId)
                .orElseThrow(() -> new CompanyNotFoundException(companyId));

        var companyCountry = resolveCompanyCountry(companyId, companyCountryId);

        CompanyRegion region = companyRegionRepository.findByIdAndCompanyCountryId(id, companyCountry.getId())
                .orElseThrow(() -> new CompanyRegionNotFoundException("Company region not found with id: " + id));

        region.setEnabled(true);
        CompanyRegion saved = companyRegionRepository.save(region);

        logger.info("CompanyRegion re-enabled: id={}, code={}", id, saved.getRegionCode());
        return toResponse(saved);
    }

    private CompanyRegionResponse toResponse(CompanyRegion region) {
        return new CompanyRegionResponse(
                region.getId(),
                region.getCompanyCountry().getId(),
                region.getCompanyCountry().getCompany().getId(),
                region.getCompanyCountry().getCountry().getId(),
                region.getRegionCode(),
                region.getRegionName(),
                region.getEnabled(),
                region.getCreatedAt(),
                region.getUpdatedAt()
        );
    }
}
