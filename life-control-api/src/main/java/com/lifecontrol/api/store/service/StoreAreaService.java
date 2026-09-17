package com.lifecontrol.api.store.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.exception.CompanyZoneNotFoundException;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.store.dto.CreateStoreAreaRequest;
import com.lifecontrol.api.store.dto.StoreAreaResponse;
import com.lifecontrol.api.store.dto.UpdateStoreAreaRequest;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
import com.lifecontrol.api.store.exception.DuplicateStoreAreaException;
import com.lifecontrol.api.store.exception.StoreAreaNotFoundException;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.model.StoreArea;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.store.repository.StoreAreaRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for store areas (level 2 of the store location tree).
 *
 * <p>Every public operation resolves — and authorizes — the full
 * company &rarr; country &rarr; region &rarr; zone &rarr; store path through
 * {@link CurrentUserContext#verifyCompanyStoreAccess} before touching an area, so scoped roles keep
 * their broadest granted scope and no area is reachable outside its own store.</p>
 */
@Service
public class StoreAreaService {

    private static final Logger logger = LoggerFactory.getLogger(StoreAreaService.class);

    private final StoreAreaRepository storeAreaRepository;
    private final CompanyStoreRepository companyStoreRepository;
    private final CompanyZoneRepository companyZoneRepository;
    private final CompanyRegionRepository companyRegionRepository;
    private final CompanyCountryRepository companyCountryRepository;
    private final CompanyRepository companyRepository;
    private final CurrentUserContext currentUserContext;

    public StoreAreaService(
            StoreAreaRepository storeAreaRepository,
            CompanyStoreRepository companyStoreRepository,
            CompanyZoneRepository companyZoneRepository,
            CompanyRegionRepository companyRegionRepository,
            CompanyCountryRepository companyCountryRepository,
            CompanyRepository companyRepository,
            CurrentUserContext currentUserContext) {
        this.storeAreaRepository = storeAreaRepository;
        this.companyStoreRepository = companyStoreRepository;
        this.companyZoneRepository = companyZoneRepository;
        this.companyRegionRepository = companyRegionRepository;
        this.companyCountryRepository = companyCountryRepository;
        this.companyRepository = companyRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Verifies access and resolves the store identified by the full nested path.
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user
     *     cannot access the requested scope
     * @throws CompanyNotFoundException                                when the company does not exist
     * @throws CompanyCountryNotFoundException                         when the company-country does not belong to the company
     * @throws CompanyRegionNotFoundException                          when the region does not belong to the company-country
     * @throws CompanyZoneNotFoundException                            when the zone does not belong to the region
     * @throws CompanyStoreNotFoundException                           when the store does not belong to the zone
     */
    private CompanyStore resolveStore(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {
        currentUserContext.verifyCompanyStoreAccess(companyId, companyCountryId, regionId, zoneId, storeId);

        companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));

        var companyCountry = companyCountryRepository
                .findByCompanyIdAndId(companyId, companyCountryId)
                .orElseThrow(() -> new CompanyCountryNotFoundException(companyCountryId));

        var region = companyRegionRepository
                .findByIdAndCompanyCountryId(regionId, companyCountry.getId())
                .orElseThrow(() -> new CompanyRegionNotFoundException("Company region not found with id: " + regionId));

        var zone = companyZoneRepository
                .findByIdAndCompanyRegionId(zoneId, region.getId())
                .orElseThrow(() -> new CompanyZoneNotFoundException("Company zone not found with id: " + zoneId));

        return companyStoreRepository
                .findByIdAndCompanyZoneId(storeId, zone.getId())
                .orElseThrow(() -> new CompanyStoreNotFoundException(storeId));
    }

    @Transactional(readOnly = true)
    public List<StoreAreaResponse> getAllAreas(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId, boolean includeDisabled) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);

        var areas = includeDisabled
                ? storeAreaRepository.findByCompanyStoreIdOrderByDisplayOrderAscAreaCodeAsc(store.getId())
                : storeAreaRepository.findByCompanyStoreIdAndEnabledTrueOrderByDisplayOrderAscAreaCodeAsc(
                        store.getId());

        return areas.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public StoreAreaResponse getAreaById(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId, UUID areaId) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);

        var area = storeAreaRepository
                .findByIdAndCompanyStoreId(areaId, store.getId())
                .orElseThrow(() -> new StoreAreaNotFoundException(areaId));

        return toResponse(area);
    }

    @Transactional
    public StoreAreaResponse createArea(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            CreateStoreAreaRequest request) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);

        if (storeAreaRepository.existsByCompanyStoreIdAndAreaCode(store.getId(), request.areaCode())) {
            throw new DuplicateStoreAreaException(
                    "Store area with code '" + request.areaCode() + "' already exists in this store");
        }

        var area = StoreArea.builder()
                .companyStore(store)
                .areaCode(request.areaCode())
                .areaName(request.areaName())
                .description(request.description())
                .displayOrder(request.displayOrder())
                .enabled(true)
                .build();

        var saved = storeAreaRepository.save(area);
        logger.info("StoreArea created: code={}, storeId={}", saved.getAreaCode(), store.getId());
        return toResponse(saved);
    }

    @Transactional
    public StoreAreaResponse updateArea(
            UUID companyId,
            UUID companyCountryId,
            UUID regionId,
            UUID zoneId,
            UUID storeId,
            UUID areaId,
            UpdateStoreAreaRequest request) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);

        var area = storeAreaRepository
                .findByIdAndCompanyStoreId(areaId, store.getId())
                .orElseThrow(() -> new StoreAreaNotFoundException(areaId));

        // Check uniqueness only when the code actually changes
        if (request.areaCode() != null
                && !area.getAreaCode().equals(request.areaCode())
                && storeAreaRepository.existsByCompanyStoreIdAndAreaCodeAndIdNot(
                        store.getId(), request.areaCode(), areaId)) {
            throw new DuplicateStoreAreaException(
                    "Store area with code '" + request.areaCode() + "' already exists in this store");
        }

        if (request.areaCode() != null) {
            area.setAreaCode(request.areaCode());
        }
        if (request.areaName() != null) {
            area.setAreaName(request.areaName());
        }
        if (request.description() != null) {
            area.setDescription(request.description());
        }
        if (request.displayOrder() != null) {
            area.setDisplayOrder(request.displayOrder());
        }

        var saved = storeAreaRepository.save(area);
        logger.info("StoreArea updated: id={}, code={}", saved.getId(), saved.getAreaCode());
        return toResponse(saved);
    }

    @Transactional
    public void deleteArea(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId, UUID areaId) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);

        var area = storeAreaRepository
                .findByIdAndCompanyStoreId(areaId, store.getId())
                .orElseThrow(() -> new StoreAreaNotFoundException(areaId));

        area.setEnabled(false);
        storeAreaRepository.save(area);

        logger.info("StoreArea soft-deleted: id={}, code={}", areaId, area.getAreaCode());
    }

    @Transactional
    public StoreAreaResponse enableArea(
            UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId, UUID areaId) {
        var store = resolveStore(companyId, companyCountryId, regionId, zoneId, storeId);

        var area = storeAreaRepository
                .findByIdAndCompanyStoreId(areaId, store.getId())
                .orElseThrow(() -> new StoreAreaNotFoundException(areaId));

        area.setEnabled(true);
        var saved = storeAreaRepository.save(area);

        logger.info("StoreArea re-enabled: id={}, code={}", areaId, saved.getAreaCode());
        return toResponse(saved);
    }

    private StoreAreaResponse toResponse(StoreArea area) {
        return new StoreAreaResponse(
                area.getId(),
                area.getCompanyStore().getId(),
                area.getAreaCode(),
                area.getAreaName(),
                area.getDescription(),
                area.getDisplayOrder(),
                area.getEnabled(),
                area.getCreatedAt(),
                area.getUpdatedAt());
    }
}
